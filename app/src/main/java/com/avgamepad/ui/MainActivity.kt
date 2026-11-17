package com.avgamepad.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { AvgTheme { AppScreen(vm) } }
    }

    /** Used by the accessibility mapping screen to learn a key. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (vm.ui.value.capturing && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            vm.onKeyCaptured(event.keyCode)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
