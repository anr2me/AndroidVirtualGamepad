package com.avgamepad

import android.app.Application
import com.avgamepad.backend.BackendManager
import com.avgamepad.data.Store

class AvgApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        BackendManager.init(this)
    }
}
