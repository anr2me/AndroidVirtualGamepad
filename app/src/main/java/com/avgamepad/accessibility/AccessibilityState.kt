package com.avgamepad.accessibility

import com.avgamepad.BuildConfig

/**
 * State of the optional accessibility service.
 * The default build does not have the service (see the Gradle property avg.accessibility).
 */
object AccessibilityState {
    /** true if this build has the accessibility service. */
    val available: Boolean get() = BuildConfig.ACCESSIBILITY

    /** true while the system has the service connected. The service sets this value. */
    @Volatile var connected: Boolean = false
}
