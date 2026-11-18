package com.avgamepad.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.avgamepad.backend.BackendManager

/**
 * Receives the binder of the helper process that the application starts directly through Shizuku
 * (see [ShellMain]). Only the shell user (2000) and root (0) can call it. The call must also have
 * the token that the application made for this start.
 */
class HelperProvider : ContentProvider() {
    companion object {
        const val METHOD_DELIVER = "deliver"
        const val KEY_TOKEN = "token"
        const val KEY_BINDER = "binder"
        const val KEY_WATCH = "watch"

        /** The helper watches this binder. When this process stops, the helper stops. */
        private val watch = Binder()
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_DELIVER) return null
        val uid = Binder.getCallingUid()
        if (uid != 2000 && uid != 0) throw SecurityException("Only the shell user or root can call this method (uid $uid)")
        val token = extras?.getString(KEY_TOKEN)
        val binder = extras?.getBinder(KEY_BINDER)
        if (token == null || binder == null || !BackendManager.onHelperBinder(token, binder)) {
            throw SecurityException("The application does not expect this helper")
        }
        return Bundle().apply { putBinder(KEY_WATCH, watch) }
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
