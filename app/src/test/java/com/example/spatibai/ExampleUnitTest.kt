package com.example.spatibai

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun parsesTimedLyricsAndOrdersMultipleTimestamps() {
        val lines = TimedLyrics.parse(
            "[00:02.50]Second\n[00:01.5][00:03.25]First"
        )

        assertEquals(3, lines.size)
        assertEquals(1500, lines[0].timeMs)
        assertEquals("First", lines[0].text)
        assertEquals(2500, lines[1].timeMs)
        assertEquals("Second", lines[1].text)
        assertEquals(3250, lines[2].timeMs)
        assertEquals("First", lines[2].text)
    }

    @Test
    fun onlyAcceptsLyricsForTheSameTrackDuration() {
        assertTrue(TimedLyrics.matchesDuration(220, 224.0))
        assertFalse(TimedLyrics.matchesDuration(220, 226.0))
        assertFalse(TimedLyrics.matchesDuration(220, Double.NaN))
        assertTrue(TimedLyrics.matchesDuration(0, Double.NaN))
    }
}