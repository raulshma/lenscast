/*
 * No-op stand-in for `transport-backend-cct`'s destination singleton,
 * replacing the excluded `com.google.android.datatransport:transport-backend-cct`
 * artifact. tasks-core's RemoteLoggingClient reads the INSTANCE field when
 * building its (stubbed) transport; the object it gets is never queried
 * further. See Datatransport.kt for the full rationale.
 */
package com.google.android.datatransport.cct

import com.google.android.datatransport.runtime.Destination

class CCTDestination : Destination {
    companion object {
        @JvmField
        val INSTANCE = CCTDestination()
    }
}
