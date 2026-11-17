package com.avgamepad.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.avgamepad.data.Store
import com.avgamepad.model.AccRule

/**
 * Non-root fallback. The system gives this service the key events of gamepads.
 * Android does not let an accessibility service create gamepad events.
 * This service can only run Android actions (Back, Home, taps) and block keys.
 */
class AgpAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: AgpAccessibilityService? = null
    }

    override fun onServiceConnected() { instance = this }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!Store.accEnabled) return false
        val fromPad = event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_DPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        if (!fromPad) return false
        val rule = Store.accRules.firstOrNull { it.keyCode == event.keyCode } ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) run(rule)
        return true // consume key down and key up
    }

    private fun run(r: AccRule) {
        when (r.action) {
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "QUICK_SETTINGS" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "POWER_DIALOG" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            "TAP" -> {
                val dm = resources.displayMetrics
                val p = Path().apply { moveTo(r.x * dm.widthPixels, r.y * dm.heightPixels) }
                val g = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(p, 0, 50)).build()
                dispatchGesture(g, null, null)
            }
            else -> {} // BLOCK
        }
    }
}
