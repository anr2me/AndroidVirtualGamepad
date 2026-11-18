package com.avgamepad.service

import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import java.lang.reflect.Method

/**
 * Calls a ContentProvider of the application from a shell process.
 * The shell process has no Context, so this object uses hidden APIs through reflection.
 * The signature of IContentProvider.call is different in each Android version.
 */
internal object ProviderCaller {
    private const val CALLER = "com.android.shell"

    fun call(authority: String, userId: Int, method: String, extras: Bundle): Bundle? {
        val provider = getProvider(authority, userId)
        // On Android 12 and later, IContentProvider has more than one "call" method. The old ones
        // are default methods that give the authority "unknown" to the provider, and the provider
        // refuses it. Select the method that has the signature of this Android version.
        val calls = Class.forName("android.content.IContentProvider").methods.filter { it.name == "call" }
        val sdk = Build.VERSION.SDK_INT
        val result = when {
            sdk >= 31 -> find(calls, 5, "android.content.AttributionSource")
                .invoke(provider, attributionSource(), authority, method, null, extras)
            sdk == 30 -> find(calls, 6, "java.lang.String")
                .invoke(provider, CALLER, null, authority, method, null, extras)
            sdk == 29 -> find(calls, 5, "java.lang.String")
                .invoke(provider, CALLER, authority, method, null, extras)
            else -> find(calls, 4, "java.lang.String")
                .invoke(provider, CALLER, method, null, extras)
        }
        return result as Bundle?
    }

    private fun find(calls: List<Method>, size: Int, first: String): Method =
        calls.firstOrNull { it.parameterTypes.size == size && it.parameterTypes[0].name == first }
            ?: throw UnsupportedOperationException(
                "IContentProvider.call has no method with $size parameters that starts with $first. Found: " +
                    calls.joinToString { m -> m.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName } }
            )

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
