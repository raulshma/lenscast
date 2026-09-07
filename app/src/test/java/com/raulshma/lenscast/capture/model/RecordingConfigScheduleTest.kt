package com.raulshma.lenscast.capture.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RecordingConfigScheduleTest {

    private fun at(hour: Int, minute: Int, second: Int = 0, millis: Int = 0): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, millis)
        }.timeInMillis

    private fun calendarOf(time: Long): Calendar = Calendar.getInstance().apply { timeInMillis = time }

    @Test
    fun `a time later today schedules today`() {
        val now = at(8, 0)
        val scheduled = RecordingConfig.scheduledStartFor(hour = 10, minute = 30, now = now)
        val cal = calendarOf(scheduled)
        assertEquals(10, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(
            calendarOf(now).get(Calendar.DAY_OF_YEAR),
            cal.get(Calendar.DAY_OF_YEAR),
        )
    }

    @Test
    fun `a time already past rolls over to tomorrow`() {
        val now = at(12, 0)
        val scheduled = RecordingConfig.scheduledStartFor(hour = 9, minute = 15, now = now)
        val cal = calendarOf(scheduled)
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, cal.get(Calendar.MINUTE))
        assertEquals(
            calendarOf(now).get(Calendar.DAY_OF_YEAR) + 1,
            cal.get(Calendar.DAY_OF_YEAR),
        )
    }

    @Test
    fun `exactly now stays today - locked boundary behavior`() {
        val now = at(10, 30)
        val scheduled = RecordingConfig.scheduledStartFor(hour = 10, minute = 30, now = now)
        assertEquals(
            calendarOf(now).get(Calendar.DAY_OF_YEAR),
            calendarOf(scheduled).get(Calendar.DAY_OF_YEAR),
        )
        assertEquals(now, scheduled)
    }

    @Test
    fun `midnight edge - late evening rolls an early slot to tomorrow`() {
        val now = at(23, 50)
        val scheduled = RecordingConfig.scheduledStartFor(hour = 0, minute = 15, now = now)
        val cal = calendarOf(scheduled)
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, cal.get(Calendar.MINUTE))
        assertTrue(scheduled > now)
        assertEquals(
            calendarOf(now).get(Calendar.DAY_OF_YEAR) + 1,
            cal.get(Calendar.DAY_OF_YEAR),
        )
    }

    @Test
    fun `bounds constants match the capture screen sliders`() {
        assertEquals(3600L, RecordingConfig.MAX_DURATION_SECONDS)
        assertEquals(3600L, RecordingConfig.MAX_REPEAT_SECONDS)
    }
}
