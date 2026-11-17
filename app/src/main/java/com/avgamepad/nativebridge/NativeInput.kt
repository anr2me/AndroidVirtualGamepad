package com.avgamepad.nativebridge

/** JNI bridge. Used only inside the privileged (root or shell) process. */
object NativeInput {
    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded(nativeLibDir: String?) {
        if (loaded) return
        try {
            System.loadLibrary("agp")
        } catch (e: UnsatisfiedLinkError) {
            if (nativeLibDir == null) throw e
            System.load("$nativeLibDir/libagp.so")
        }
        loaded = true
    }

    fun errName(e: Int): String = when (e) {
        1 -> "EPERM (not permitted)"
        2 -> "ENOENT (not found)"
        5 -> "EIO"
        13 -> "EACCES (permission denied)"
        16 -> "EBUSY"
        19 -> "ENODEV (device gone)"
        22 -> "EINVAL"
        else -> "errno $e"
    }

    @JvmStatic external fun openDevice(path: String, write: Boolean): Int
    @JvmStatic external fun closeFd(fd: Int)
    @JvmStatic external fun grab(fd: Int, on: Boolean): Int
    @JvmStatic external fun deviceName(fd: Int): String?
    @JvmStatic external fun deviceIds(fd: Int): IntArray?
    @JvmStatic external fun capabilities(fd: Int, evType: Int): IntArray
    @JvmStatic external fun absInfo(fd: Int, code: Int): IntArray?
    @JvmStatic external fun readEvents(fd: Int, out: IntArray, timeoutMs: Int): Int
    @JvmStatic external fun writeEvents(fd: Int, events: IntArray, count: Int): Int
    @JvmStatic external fun uinputProbe(): Int
    @JvmStatic external fun uinputCreate(
        name: String, bus: Int, vendor: Int, product: Int, version: Int,
        keys: IntArray, absCodes: IntArray, absMin: IntArray, absMax: IntArray,
        absFuzz: IntArray, absFlat: IntArray, ff: Boolean,
    ): Int
    @JvmStatic external fun uinputDestroy(fd: Int)

    // Force feedback (rumble)
    @JvmStatic external fun ffRead(fd: Int, out: IntArray, timeoutMs: Int): Int
    @JvmStatic external fun ffUpload(fd: Int, id: Int, strong: Int, weak: Int, lengthMs: Int): Int
    @JvmStatic external fun ffErase(fd: Int, id: Int): Int
    @JvmStatic external fun ffSupported(fd: Int): Boolean
}
