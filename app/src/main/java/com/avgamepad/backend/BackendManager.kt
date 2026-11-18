package com.avgamepad.backend

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.avgamepad.BuildConfig
import com.avgamepad.IGamepadService
import com.avgamepad.model.BackendType
import com.avgamepad.service.GamepadRootService
import com.avgamepad.service.ShellMain
import com.avgamepad.service.ShizukuUserService
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.UUID

data class BackendState(
    val type: BackendType? = null,
    val connected: Boolean = false,
    val message: String = "Not connected",
)

/** Connects the UI process to the privileged helper (root or Shizuku). */
object BackendManager {
    private lateinit var app: Application
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(BackendState())
    val state: StateFlow<BackendState> = _state.asStateFlow()

    @Volatile var service: IGamepadService? = null
        private set
    private var current: BackendType? = null
    private var shizukuArgs: Shizuku.UserServiceArgs? = null
    private const val REQ_SHIZUKU = 4711
    private const val PREFS = "backend"
    private const val KEY_DIRECT = "shizuku_direct"

    // Shizuku has two ways to start the helper: the Shizuku user service, and a direct start with
    // app_process (see ShellMain). The user service does not start on some devices.
    private var shizukuDirect = false          // the current attempt uses the direct start
    private var shizukuFallbackDone = false    // the other way was tried
    @Volatile private var directToken: String? = null
    private var directProcess: Process? = null

    /** Time in milliseconds that the Shizuku helper has to connect. */
    private const val HELPER_TIMEOUT_MS = 10_000L

    private val helperTimeout = Runnable {
        if (service == null && current == BackendType.SHIZUKU) shizukuStartFailed("The helper did not connect in time.")
    }

    fun init(application: Application) {
        app = application
        PhoneRumble.init(application)
        _state.onEach { Diagnostics.record(it.message) }.launchIn(scope)
        Shizuku.addRequestPermissionResultListener(permListener)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            main.removeCallbacks(helperTimeout)
            val s = IGamepadService.Stub.asInterface(binder)
            try {
                s.init(app.applicationInfo.nativeLibraryDir)
                s.setRumbleCallback(PhoneRumble)
                service = s
                val who = if (s.uid == 0) "root" else "shell (uid ${s.uid})"
                _state.value = BackendState(current, true, "Connected. Helper runs as $who.")
                // Remember the start method that operates on this device.
                if (current == BackendType.SHIZUKU) {
                    runCatching { prefs().edit().putBoolean(KEY_DIRECT, shizukuDirect).apply() }
                }
            } catch (t: Throwable) {
                service = null
                _state.value = BackendState(current, false, "Helper failed: ${t.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            main.removeCallbacks(helperTimeout)
            service = null
            _state.value = BackendState(current, false, "Helper disconnected.")
        }

        override fun onBindingDied(name: ComponentName) {
            main.removeCallbacks(helperTimeout)
            service = null
            _state.value = BackendState(current, false, "Helper process died.")
        }

        override fun onNullBinding(name: ComponentName) {
            main.removeCallbacks(helperTimeout)
            service = null
            _state.value = BackendState(current, false, "Helper did not give a binder.")
        }
    }

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) bindShizuku()
        else _state.value = BackendState(current, false, "Shizuku permission denied.")
    }

    fun connect(type: BackendType) = onMain { connectNow(type) }
    fun disconnect() = onMain { disconnectNow() }

    /** RootService.bind and unbind must run on the main thread. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** null = Shizuku is not running, true = running and permission granted, false = permission missing. */
    fun shizukuPermission(): Boolean? = try {
        if (!Shizuku.pingBinder()) null else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { null }

    private fun connectNow(type: BackendType) {
        disconnectNow()
        current = type
        when (type) {
            BackendType.ROOT -> connectRoot()
            BackendType.SHIZUKU -> connectShizuku()
            BackendType.ACCESSIBILITY ->
                _state.value = BackendState(type, true, "Accessibility mode. Keys map to Android actions only.")
        }
    }

    private fun disconnectNow() {
        main.removeCallbacks(helperTimeout)
        val old = service
        service = null
        runCatching { RootService.unbind(conn) }
        stopShizukuHelper(old)
        _state.value = BackendState(current, false, "Not connected")
    }

    private fun connectRoot() {
        _state.value = BackendState(BackendType.ROOT, false, "Requesting root access...")
        Thread {
            val granted = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
            main.post {
                if (!granted) {
                    _state.value = BackendState(BackendType.ROOT, false, "Root access denied or not available.")
                } else {
                    RootService.bind(Intent(app, GamepadRootService::class.java), conn)
                }
            }
        }.start()
    }

    private fun connectShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                _state.value = BackendState(BackendType.SHIZUKU, false, "Shizuku is not running. Start Shizuku first.")
                return
            }
            if (Shizuku.isPreV11()) {
                _state.value = BackendState(BackendType.SHIZUKU, false, "Shizuku version is too old.")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bindShizuku()
            else {
                _state.value = BackendState(BackendType.SHIZUKU, false, "Waiting for Shizuku permission...")
                Shizuku.requestPermission(REQ_SHIZUKU)
            }
        } catch (t: Throwable) {
            _state.value = BackendState(BackendType.SHIZUKU, false, "Shizuku error: ${t.message}")
        }
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun bindShizuku() {
        shizukuFallbackDone = false
        startShizuku(prefs().getBoolean(KEY_DIRECT, false))
    }

    /** Starts the helper with the Shizuku user service, or with the direct start. */
    private fun startShizuku(direct: Boolean) {
        shizukuDirect = direct
        _state.value = BackendState(
            BackendType.SHIZUKU, false,
            if (direct) "Starting Shizuku helper (direct start)..." else "Starting Shizuku helper...",
        )
        try {
            if (direct) startDirect() else bindUserService()
        } catch (t: Throwable) {
            shizukuStartFailed("The start failed: ${(t.cause ?: t).message}")
            return
        }
        main.removeCallbacks(helperTimeout)
        main.postDelayed(helperTimeout, HELPER_TIMEOUT_MS)
    }

    private fun bindUserService() {
        val args = Shizuku.UserServiceArgs(ComponentName(app.packageName, ShizukuUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("avg_helper")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        shizukuArgs = args
        Shizuku.bindUserService(args, conn)
    }

    private fun startDirect() {
        directToken = UUID.randomUUID().toString()
        val p = ShizukuShell.startHelper(app, directToken!!)
        directProcess = p
        ShizukuShell.drain(p)
        // A helper that stops before it connects is a failure. Do not wait for the timeout.
        Thread {
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            main.post {
                if (directProcess === p && service == null && current == BackendType.SHIZUKU) {
                    main.removeCallbacks(helperTimeout)
                    shizukuStartFailed("The helper stopped before it connected (exit code $code).")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Tries the other start method one time. If that fails too, shows the failure. */
    private fun shizukuStartFailed(reason: String) {
        if (current != BackendType.SHIZUKU) return
        Diagnostics.record(reason)
        if (!shizukuFallbackDone) {
            shizukuFallbackDone = true
            stopShizukuHelper(null)
            startShizuku(!shizukuDirect)
        } else {
            _state.value = BackendState(
                BackendType.SHIZUKU, false,
                "Shizuku helper did not start. Select Diagnostics to see the log.",
            )
        }
    }

    /** Stops the user service and the directly started process. [connected] is the helper that is connected now. */
    private fun stopShizukuHelper(connected: IGamepadService?) {
        shizukuArgs?.let { runCatching { Shizuku.unbindUserService(it, conn, true) } }
        shizukuArgs = null
        val p = directProcess
        directProcess = null
        directToken = null
        if (p != null) {
            // Ask the helper to stop. Then stop the process, if it is still there.
            Thread {
                if (connected != null) runCatching { connected.destroy() }
                runCatching { p.destroy() }
            }.start()
        }
    }

    /**
     * HelperProvider calls this when the directly started helper gives its binder.
     * It runs on a binder thread. Returns false if the token is wrong.
     */
    fun onHelperBinder(token: String, binder: IBinder): Boolean {
        if (token != directToken) return false
        main.post {
            if (token != directToken || current != BackendType.SHIZUKU) return@post
            val name = ComponentName(app.packageName, ShellMain::class.java.name)
            runCatching {
                binder.linkToDeath(IBinder.DeathRecipient {
                    main.post { if (service?.asBinder() === binder) conn.onServiceDisconnected(name) }
                }, 0)
            }
            conn.onServiceConnected(name, binder)
        }
        return true
    }

    /** Runs a binder call off the main thread. */
    suspend fun <T> call(block: (IGamepadService) -> T): T = withContext(Dispatchers.IO) {
        val s = service ?: throw IllegalStateException("Not connected")
        block(s)
    }
}
