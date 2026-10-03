/*
 * No-op stand-ins for `transport-runtime`'s entry points, replacing the
 * excluded `com.google.android.datatransport:transport-runtime` artifact.
 * tasks-core's RemoteLoggingClient calls these during construction; the
 * factory it gets back drops every event. See Datatransport.kt for the full
 * rationale. Do not add behavior here.
 */
package com.google.android.datatransport.runtime

import android.content.Context
import com.google.android.datatransport.Encoding
import com.google.android.datatransport.Event
import com.google.android.datatransport.Transport
import com.google.android.datatransport.TransportFactory
import com.google.android.datatransport.Transformer

/** Marker for a log destination; only CCTDestination is ever passed. */
interface Destination

internal object NoopRuntimeFactory : TransportFactory {
    override fun getTransport(
        name: String,
        type: Class<*>,
        encoding: Encoding,
        transformer: Transformer<*, *>,
    ): Transport<*> = NoopRuntimeTransport
}

private object NoopRuntimeTransport : Transport<Any?> {
    override fun send(event: Event<Any?>) = Unit
}

/** No-op replacement: initializes nothing, hands out a factory that drops events. */
class TransportRuntime private constructor() {
    fun newFactory(destination: Destination): TransportFactory = NoopRuntimeFactory

    companion object {
        private val instance = TransportRuntime()

        @JvmStatic
        fun initialize(context: Context) = Unit

        @JvmStatic
        fun getInstance(): TransportRuntime = instance
    }
}
