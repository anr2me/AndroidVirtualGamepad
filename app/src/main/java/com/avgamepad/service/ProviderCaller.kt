package com.avgamepad.service

import android.os.Binder
import android.os.Bundle
import android.os.IBinder

/**
 * Calls a ContentProvider of the application from a shell process.
 * The shell process has no Context, so this object uses hidden APIs through reflection.
 * The signature of IContentProvider.call is different in each Android version.
 */
internal object ProviderCaller {
    private const val CALLER = "com.android.shell"

    fun call(authority: String, userId: Int, method: String, extras: Bundle): Bundle? {
        val provider = getProvider(authority, userId)
        val call = Class.forName("android.content.IContentProvider").methods.first { it.name == "call" }
        val t = call.parameterTypes
        val result = when {
            // Android 9 and earlier
            t.size == 4 -> call.invoke(provider, CALLER, method, null, extras)
            // Android 10
            t.size == 5 && t[0] == String::class.java ->
                call.invoke(provider, CALLER, authority, method, null, extras)
            // Android 11
            t.size == 6 -> call.invoke(provider, CALLER, null, authority, method, null, extras)
            // Android 12 and later
            t.size == 5 -> call.invoke(provider, attributionSource(), authority, method, null, extras)
            else -> throw UnsupportedOperationException(
                "IContentProvider.call has an unknown signature: " + t.joinToString { it.simpleName }
            )
        }
        return result as Bundle?
    }

    private fun getProvider(authority: String, userId: Int): Any {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "activity") as IBinder
        val am = Class.forName("android.app.IActivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val get = Class.forName("android.app.IActivityManager").methods
            .first { it.name == "getContentProviderExternal" }
        val token = Binder()
        val holder = when (get.parameterTypes.size) {
            4 -> get.invoke(am, authority, userId, token, "*avg*")   // Android 10 and later
            3 -> get.invoke(am, authority, userId, token)
            else -> throw UnsupportedOperationException("getContentProviderExternal has an unknown signature")
        } ?: error("The provider $authority is not available")
        return holder.javaClass.getField("provider").get(holder) ?: error("The provider $authority is not available")
    }

    private fun attributionSource(): Any {
        val builder = Class.forName("android.content.AttributionSource\$Builder")
            .getConstructor(Int::class.javaPrimitiveType).newInstance(android.os.Process.myUid())
        builder.javaClass.getMethod("setPackageName", String::class.java).invoke(builder, CALLER)
        return builder.javaClass.getMethod("build").invoke(builder)!!
    }
}
