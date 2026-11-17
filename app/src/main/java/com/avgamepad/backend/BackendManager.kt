package com.avgamepad.backend

import android.app.Application
import android.content.ComponentName
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
import com.avgamepad.service.ShizukuUserService
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class BackendState(
    val type: BackendType? = null,
    val connected: Boolean = false,
    val message: String = "Not connected",
)

/** Connects the UI process to the privileged helper (root or Shizuku). */
object BackendManager {
    private lateinit var app: Application
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(BackendState())
    val state: StateFlow<BackendState> = _state.asStateFlow()

    @Volatile var service: IGamepadService? = null
        private set
    private var current: BackendType? = null
    private var shizukuArgs: Shizuku.UserServiceArgs? = null
    private const val REQ_SHIZUKU = 4711

    fun init(application: Application) {
        app = application
        PhoneRumble.init(application)
        Shizuku.addRequestPermissionResultListener(permListener)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = IGamepadService.Stub.asInterface(binder)
            try {
                s.init(app.applicationInfo.nativeLibraryDir)
                s.setRumbleCallback(PhoneRumble)
                service = s
                val who = if (s.uid == 0) "root" else "shell (uid ${s.uid})"
                _state.value = BackendState(current, true, "Connected. Helper runs as $who.")
            } catch (t: Throwable) {
                service = null
                _state.value = BackendState(current, false, "Helper failed: ${t.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            _state.value = BackendState(current, false, "Helper disconnected.")
        }
    }

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) bindShizuku()
        else _state.value = BackendState(current, false, "Shizuku permission denied.")
    }

    fun connect(type: BackendType) {
        disconnect()
        current = type
        when (type) {
            BackendType.ROOT -> connectRoot()
            BackendType.SHIZUKU -> connectShizuku()
            BackendType.ACCESSIBILITY ->
                _state.value = BackendState(type, true, "Accessibility mode. Keys map to Android actions only.")
        }
    }

    fun disconnect() {
        service = null
        runCatching { RootService.unbind(conn) }
        shizukuArgs?.let { runCatching { Shizuku.unbindUserService(it, conn, true) } }
        shizukuArgs = null
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

    private fun bindShizuku() {
        _state.value = BackendState(BackendType.SHIZUKU, false, "Starting Shizuku helper...")
        val args = Shizuku.UserServiceArgs(ComponentName(app.packageName, ShizukuUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("avg_helper")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        shizukuArgs = args
        Shizuku.bindUserService(args, conn)
    }

    /** Runs a binder call off the main thread. */
    suspend fun <T> call(block: (IGamepadService) -> T): T = withContext(Dispatchers.IO) {
        val s = service ?: throw IllegalStateException("Not connected")
        block(s)
    }
}
