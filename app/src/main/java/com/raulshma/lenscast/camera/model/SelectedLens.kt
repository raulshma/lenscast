package com.raulshma.lenscast.camera.model

import androidx.camera.core.CameraSelector

/**
 * The one selected-lens fact: the [CameraSelector] the next bind uses, whether
 * it faces front, and its index into the published lens inventory. The three
 * must move together because they describe one lens from three angles — the
 * selector is what [com.raulshma.lenscast.camera.CameraService] binds with,
 * the facing seeds the empty-inventory front/back toggle, and the index is
 * what the lens row highlights and the next cycle step starts from — and the
 * former three-store version (a plain selector var plus two flows) could
 * half-update: the enumeration-failure fallback reset only the index, leaving
 * the selector and facing stale. One write here publishes all three.
 *
 * [com.raulshma.lenscast.camera.CameraService] is the only writer (lens
 * selection, the switch fallback, and both enumeration outcomes, each a
 * single atomic update); the lens row, the Web API's lens status, and the
 * facing mirror read the published flow.
 */
data class SelectedLens(
    val selector: CameraSelector,
    val isFront: Boolean,
    val index: Int,
)
