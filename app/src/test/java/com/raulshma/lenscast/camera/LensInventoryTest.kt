package com.raulshma.lenscast.camera

import androidx.camera.core.CameraSelector
import com.raulshma.lenscast.camera.model.CameraLensInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class LensInventoryTest {

    private fun lens(
        id: String,
        facing: Int = CameraSelector.LENS_FACING_BACK,
        focal: Float = 4.3f,
    ) = CameraLensInfo(
        id = id,
        label = id,
        lensFacing = facing,
        focalLength = focal,
        cameraSelector = if (facing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        },
    )

    @Test
    fun `front lens is always front`() {
        assertEquals("Front", LensInventory.buildLabel(CameraSelector.LENS_FACING_FRONT, 99f, "1"))
        assertEquals("Front", LensInventory.buildLabel(CameraSelector.LENS_FACING_FRONT, 0f, "1"))
    }

    @Test
    fun `back labels follow focal length bands`() {
        val back = CameraSelector.LENS_FACING_BACK
        assertEquals("Camera 0", LensInventory.buildLabel(back, 0f, "0"))
        assertEquals("Camera 0", LensInventory.buildLabel(back, -1f, "0"))
        assertEquals("Ultrawide", LensInventory.buildLabel(back, 1.8f, "2"))
        assertEquals("Wide", LensInventory.buildLabel(back, 4.3f, "0"))
        assertEquals("2x", LensInventory.buildLabel(back, 6f, "3"))
        assertEquals("3x", LensInventory.buildLabel(back, 12f, "4"))
        assertEquals("5x", LensInventory.buildLabel(back, 20f, "5"))
        assertEquals("30mm", LensInventory.buildLabel(back, 30f, "6"))
    }

    @Test
    fun `oem duplicates collapse on facing plus focal length`() {
        val lenses = listOf(
            lens("0", focal = 4.3f),
            lens("dup", focal = 4.3f),
            lens("front", facing = CameraSelector.LENS_FACING_FRONT, focal = 4.3f),
        )
        val distinct = LensInventory.deduplicate(lenses)
        assertEquals(listOf("0", "front"), distinct.map { it.id })
    }

    @Test
    fun `sort puts back lenses first by ascending focal length`() {
        val lenses = listOf(
            lens("front", facing = CameraSelector.LENS_FACING_FRONT, focal = 2f),
            lens("tele", focal = 12f),
            lens("wide", focal = 4.3f),
            lens("ultra", focal = 1.8f),
        )
        val sorted = LensInventory.sortLenses(lenses)
        assertEquals(listOf("ultra", "wide", "tele", "front"), sorted.map { it.id })
    }

    @Test
    fun `default is the first back lens`() {
        val sorted = LensInventory.sortLenses(
            listOf(
                lens("front", facing = CameraSelector.LENS_FACING_FRONT),
                lens("wide", focal = 4.3f),
                lens("ultra", focal = 1.8f),
            ),
        )
        assertEquals(0, LensInventory.defaultBackIndex(sorted))
    }

    @Test
    fun `default without any back lens is zero`() {
        assertEquals(0, LensInventory.defaultBackIndex(emptyList()))
        assertEquals(
            0,
            LensInventory.defaultBackIndex(
                listOf(lens("front", facing = CameraSelector.LENS_FACING_FRONT)),
            ),
        )
    }

    @Test
    fun `fallback covers back and front`() {
        val fallback = LensInventory.fallbackLenses()
        assertEquals(2, fallback.size)
        assertEquals("Back", fallback[0].label)
        assertEquals("Front", fallback[1].label)
        assertEquals(0, LensInventory.defaultBackIndex(fallback))
    }

    @Test
    fun `next index advances and wraps around the inventory`() {
        assertEquals(1, LensInventory.nextIndex(0, 4))
        assertEquals(3, LensInventory.nextIndex(2, 4))
        assertEquals(0, LensInventory.nextIndex(3, 4))
        // A single-lens inventory always cycles back to itself.
        assertEquals(0, LensInventory.nextIndex(0, 1))
    }

    @Test
    fun `empty-inventory fallback toggles front to back`() {
        assertEquals(
            CameraSelector.DEFAULT_BACK_CAMERA to false,
            LensInventory.fallbackSelector(currentFront = true),
        )
    }

    @Test
    fun `empty-inventory fallback toggles back to front`() {
        assertEquals(
            CameraSelector.DEFAULT_FRONT_CAMERA to true,
            LensInventory.fallbackSelector(currentFront = false),
        )
    }
}
