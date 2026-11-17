package com.avgamepad.backend

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.avgamepad.IRumbleCallback

/** Receives rumble requests from the helper process and plays them on the phone vibrator. */
object PhoneRumble : IRumbleCallback.Stub() {
    private var vib: Vibrator? = null

    @Suppress("DEPRECATION")
    fun init(ctx: Context) {
        vib = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    @Suppress("DEPRECATION")
    override fun onRumble(slot: Int, strong: Int, weak: Int, durationMs: Int) {
        val v = vib ?: return
        if (!v.hasVibrator()) return
        val mag = maxOf(strong, weak)
        if (mag <= 0) { v.cancel(); return }
        if (Build.VERSION.SDK_INT < 26) {
            v.vibrate(if (durationMs > 0) durationMs.toLong() else 1000L)
            return
        }
        val amp = if (v.hasAmplitudeControl()) (mag * 255 / 65535).coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
        val effect = if (durationMs > 0) {
            VibrationEffect.createOneShot(durationMs.toLong(), amp)
        } else {
            // length 0 = play until stop
            VibrationEffect.createWaveform(longArrayOf(0, 1000), intArrayOf(0, if (amp == VibrationEffect.DEFAULT_AMPLITUDE) 255 else amp), 0)
        }
        // These usages are allowed while the app runs in the background (the game is in front).
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_PHYSICAL_EMULATION).build())
        } else {
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }
}
