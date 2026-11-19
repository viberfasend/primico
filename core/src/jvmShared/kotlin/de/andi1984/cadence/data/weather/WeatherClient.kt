package de.andi1984.cadence.data.weather

import de.andi1984.cadence.data.http.defaultHttpClient
import de.andi1984.cadence.domain.weather.WeatherDay
import de.andi1984.cadence.domain.weather.isDryDay
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * The Open-Meteo client: today's coarse weather for a user-set location (ADR 0007, decision 2).
 * Open-Meteo is free and needs no API key, and HTTPS+JSON is identical on Android and the desktop,
 * so — exactly like sync (ADR 0002, decision 7) — this is a plain class in `:core` on the shared
 * [defaultHttpClient] recipe, not a port.
 *
 * **The request is deliberately tiny:** `GET /v1/forecast?…&daily=weather_code,precipitation_probability_max&forecast_days=1&timezone=auto`
 * — two daily values in, one [WeatherDay] out, with the dry/raining judgement made by the pure
 * [isDryDay] rule rather than by the server. A city name is resolved once through the geocoding
 * endpoint (`/v1/search`, also key-free) and cached in memory for the process; `"lat,lon"` skips
 * geocoding entirely, which is what keeps the feature permission-free (no device location).
 *
 * **Failure is a null, never an exception and never a "good" day.** Any HTTP error, malformed
 * body or network trouble answers `null` from [today] — unknown weather is never shown as good
 * weather — and a geocoding answer that arrived is cached (a city the API does not know is not
 * re-asked every hour), while a *failed* lookup is not cached, so a later round can retry it.
 */
class WeatherClient(
    httpClient: HttpClient? = null,
    private val forecastBaseUrl: String = FORECAST_URL,
    private val geocodingBaseUrl: String = GEOCODING_URL,
) {

    private val http: HttpClient = httpClient ?: defaultHttpClient()

    /**
     * Resolved coordinates per location string — including "not found" (a null value), because
     * only *failures* are worth retrying. Guarded by itself: the ViewModel polls from one
     * coroutine today, but the cache should not care.
     */
    private val geocoded = java.util.Collections.synchronizedMap(mutableMapOf<String, LatLon?>())

    /**
     * Today's weather for [location] — a city name ("Berlin") or `"lat,lon"` — or null when it
     * could not be fetched. Blank and unresolvable locations answer null without a request.
     */
    suspend fun today(location: String): WeatherDay? {
        val coords = try {
            resolve(location)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        } ?: return null
        return try {
            val response = http.get(forecastBaseUrl) {
                parameter("latitude", coords.latitude)
                parameter("longitude", coords.longitude)
                parameter("daily", "weather_code,precipitation_probability_max")
                parameter("forecast_days", 1)
                parameter("timezone", "auto")
            }
            parseForecastDay(response.bodyOrThrow())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Coordinates for [location], geocoding a city name at most once per process. */
    private suspend fun resolve(location: String): LatLon? {
        val trimmed = location.trim()
        if (trimmed.isEmpty()) return null
        parseCoordinates(trimmed)?.let { return it }
        synchronized(geocoded) {
            if (trimmed in geocoded) return geocoded.getValue(trimmed)
        }
        val response = http.get(geocodingBaseUrl) {
            parameter("name", trimmed)
            parameter("count", 1)
            parameter("language", "en")
            parameter("format", "json")
        }
        val resolved = parseGeocode(response.bodyOrThrow())
        synchronized(geocoded) { geocoded[trimmed] = resolved }
        return resolved
    }

    private suspend fun HttpResponse.bodyOrThrow(): String {
        val body = bodyAsText()
        if (status.value !in 200..299) throw WeatherHttpException(status.value, body.take(BODY_LIMIT))
        return body
    }

    companion object {
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"

        private const val BODY_LIMIT = 500
    }
}

/** An answer that is not a success — the caller maps every one of these to "unknown weather". */
internal class WeatherHttpException(val status: Int, val body: String) :
    Exception("Open-Meteo returned $status: $body")

/** Coordinates as Open-Meteo takes them. */
internal data class LatLon(val latitude: Double, val longitude: Double)

private val WeatherJson: Json = Json { ignoreUnknownKeys = true }

private val COORDINATES = Regex("""(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""")

/**
 * `"52.52, 13.405"` (any spacing, either sign) as coordinates, or null for anything else —
 * including pairs outside the valid ranges, which a geocoding call would refuse anyway.
 */
internal fun parseCoordinates(location: String): LatLon? {
    val match = COORDINATES.matchEntire(location.trim()) ?: return null
    val latitude = match.groupValues[1].toDouble()
    val longitude = match.groupValues[2].toDouble()
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return LatLon(latitude, longitude)
}

@Serializable
private data class GeocodeResponse(
    @SerialName("results") val results: List<GeocodeResult>? = null,
) {
    @Serializable
    data class GeocodeResult(
        @SerialName("latitude") val latitude: Double,
        @SerialName("longitude") val longitude: Double,
    )
}

/** The first geocoding hit for a name, or null when the API knows no such place. */
internal fun parseGeocode(body: String): LatLon? =
    runCatching { WeatherJson.decodeFromString<GeocodeResponse>(body) }
        .getOrNull()
        ?.results
        ?.firstOrNull()
        ?.let { LatLon(it.latitude, it.longitude) }

@Serializable
private data class ForecastResponse(
    @SerialName("daily") val daily: Daily? = null,
) {
    @Serializable
    data class Daily(
        @SerialName("time") val time: List<String>? = null,
        @SerialName("weather_code") val weatherCode: List<Int>? = null,
        @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Int>? = null,
    )
}

/**
 * The first day of a forecast response, or null when any of the three values the rule needs is
 * missing — a partial answer is unknown weather, not a dry day.
 */
internal fun parseForecastDay(body: String): WeatherDay? {
    val daily = runCatching { WeatherJson.decodeFromString<ForecastResponse>(body) }
        .getOrNull()
        ?.daily
        ?: return null
    val date = daily.time?.firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: return null
    val weatherCode = daily.weatherCode?.firstOrNull() ?: return null
    val precipProbMax = daily.precipitationProbabilityMax?.firstOrNull() ?: return null
    return WeatherDay(date = date, dry = isDryDay(weatherCode, precipProbMax))
}
