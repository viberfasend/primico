package de.andi1984.cadence.domain.weather

import java.time.LocalDate

/**
 * One day's weather, reduced to the single question the outdoor section on Today asks: is it
 * dry out there? "Coarse" is the requirement — no hourly values, no temperature, no icons beyond
 * the section heading — so the whole answer fits in one boolean (ADR 0007, decision 2).
 */
data class WeatherDay(
    val date: LocalDate,
    /** True only when the day is known not to rain — see [isDryDay]. */
    val dry: Boolean,
)

/**
 * The WMO weather interpretation codes (Open-Meteo's `weather_code`) that count as dry: clear,
 * mainly clear, partly cloudy and overcast (0–3), plus fog and depositing rime fog (45, 48).
 *
 * Everything precipitation-shaped is not dry — drizzle (51, 53, 55, 56, 57), rain (61, 63, 65,
 * 66, 67), snow and snow grains (71, 73, 75, 77), rain and snow showers (80, 81, 82, 85, 86) and
 * thunderstorm, with or without hail (95, 96, 99) — and so is any code outside the table: an
 * unrecognised code is unknown weather, never good weather.
 */
val DRY_WEATHER_CODES: Set<Int> = setOf(0, 1, 2, 3, 45, 48)

/**
 * The maximum precipitation probability (percent, the daily `precipitation_probability_max`)
 * below which a dry-coded day still counts as dry. 50 and above is "it might rain", which is not
 * a good-weather day.
 */
const val DRY_PRECIPITATION_PROBABILITY_MAX: Int = 50

/**
 * Whether a day with this WMO [weatherCode] and this maximum precipitation probability
 * [precipProbMax] counts as dry. This function is the feature's whole judgement, so it is pure
 * and exhaustively tested over the code table.
 */
fun isDryDay(weatherCode: Int, precipProbMax: Int): Boolean =
    weatherCode in DRY_WEATHER_CODES && precipProbMax < DRY_PRECIPITATION_PROBABILITY_MAX
