/*
 * No-op stand-ins for the public API of Google's `transport-api` artifact
 * (`com.google.android.datatransport:transport-api`).
 *
 * MediaPipe `tasks-core` is a prebuilt AAR whose `RemoteLoggingClient`
 * queues usage statistics for upload to Google through the datatransport
 * CCT (Firebase logging) backend every time a task runner is created. The
 * real transport jars are excluded from every Gradle configuration in
 * app/build.gradle.kts, so these classes exist purely to satisfy tasks-core's
 * linkage at runtime; every method discards its input and nothing is ever
 * transmitted. Signatures mirror the upstream API surface that
 * `RemoteLoggingClient` references (see its bytecode: Transport.send,
 * TransportFactory.getTransport, Encoding.of, Event.ofData, and a
 * Transformer lambda). Do not add behavior here.
 */
package com.google.android.datatransport

/** No-op: events handed to [send] are dropped on the floor. */
interface Transport<T> {
    fun send(event: Event<T>)
}

/** No-op: hands out the shared [NoopTransport] regardless of arguments. */
interface TransportFactory {
    fun getTransport(
        name: String,
        type: Class<*>,
        encoding: Encoding,
        transformer: Transformer<*, *>,
    ): Transport<*>
}

/** Payload wrapper; the no-op pipeline never reads [data]. */
class Event<T> private constructor(
    @Suppress("unused") val data: T?,
) {
    companion object {
        @JvmStatic
        fun <T> ofData(data: T): Event<T> = Event(data)
    }
}

/** Wire-format tag; retained for signature compatibility only. */
class Encoding private constructor(
    @Suppress("unused") val name: String,
) {
    companion object {
        @JvmStatic
        fun of(name: String): Encoding = Encoding(name)
    }
}

/** Encoder SAM that tasks-core instantiates via LambdaMetafactory; never invoked. */
fun interface Transformer<F, out B> {
    fun apply(value: F): B
}

private object NoopTransportFactory : TransportFactory {
    override fun getTransport(
        name: String,
        type: Class<*>,
        encoding: Encoding,
        transformer: Transformer<*, *>,
    ): Transport<*> = NoopTransport
}

private object NoopTransport : Transport<Any?> {
    override fun send(event: Event<Any?>) = Unit
}
