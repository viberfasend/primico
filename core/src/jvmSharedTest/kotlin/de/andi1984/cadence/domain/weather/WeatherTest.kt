package de.andi1984.cadence.domain.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isDryDay] is the whole feature's judgement, so it gets the whole test budget: the complete WMO
 * code table, the precipitation-probability boundary, and the combinations of the two.
 */
class WeatherTest {

    @Test
    fun `the whole WMO code table — dry only for clear to overcast and fog`() {
        for (code in -1..100) {
            val expected = code in DRY_WEATHER_CODES
            assertEquals("weather_code $code", expected, isDryDay(code, precipProbMax = 0))
        }
    }

    @Test
    fun `every precipitation-shaped code fails even with zero probability`() {
        val wet = listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)
        wet.forEach { code -> assertFalse("weather_code $code", isDryDay(code, precipProbMax = 0)) }
    }

    @Test
    fun `an unrecognised code outside the table is not dry`() {
        assertFalse(isDryDay(-1, 0))
        assertFalse(isDryDay(100, 0))
        assertFalse(isDryDay(Int.MAX_VALUE, 0))
    }

    @Test
    fun `the probability boundary — 49 is dry, 50 is not`() {
        assertTrue(isDryDay(0, precipProbMax = 49))
        assertFalse(isDryDay(0, precipProbMax = 50))
        assertFalse(isDryDay(0, precipProbMax = 100))
    }

    @Test
    fun `a wet code stays wet whatever the probability says`() {
        assertFalse(isDryDay(61, precipProbMax = 0))
        assertFalse(isDryDay(95, precipProbMax = 0))
    }
}
