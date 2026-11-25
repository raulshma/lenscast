package com.raulshma.lenscast.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * The store-test harness for the default-first race: a dispatcher that
 * parks every dispatched block forever. A [SettingsDataStore] built on a
 * scope with this dispatcher starts its eagerly-shared collectors, but they
 * queue up and never execute — so each shared StateFlow stays at its
 * descriptor default (the empty/zero placeholder) for the whole test while
 * the savers edit the DataStore file directly. That is exactly the state a
 * fresh process serves until DataStore's first read lands, held open
 * deterministically: a test can seed disk truth through the store's own
 * savers, assert the flow still reads the default, and then prove a
 * disk-truth reader ([SettingsDataStore.settingsFromDisk] and the writers
 * anchored on it) answers from the file, never from the flow. Real
 * DataStore, real file; only the share-in scope is frozen.
 *
 * Stateless, so a fresh instance per store construction is equivalent to
 * sharing one — each test keeps its own store's collectors parked.
 */
internal fun parkedDispatcher(): CoroutineDispatcher = object : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        // Parked on purpose — see the KDoc.
    }
}
