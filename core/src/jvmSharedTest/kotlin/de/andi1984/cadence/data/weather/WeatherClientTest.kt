package de.andi1984.cadence.data.weather

import de.andi1984.cadence.data.sync.RecordingHttp
import de.andi1984.cadence.data.sync.respondJson
import de.andi1984.cadence.data.sync.unexpected
import de.andi1984.cadence.domain.weather.WeatherDay
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/**
 * The weather client through Ktor's own [io.ktor.client.engine.mock.MockEngine] — the
 * `CadenceSyncRoundTest` pattern: every response is written by the test that needs it, and
 * [RecordingHttp] records what was actually asked so the caching and URL shapes are pinned too.
 */
class WeatherClientTest {

    private val clients = mutableListOf<HttpClient>()

    @After
    fun close() {
        clients.forEach { it.close() }
    }

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Pair<WeatherClient, RecordingHttp> {
        val recording = RecordingHttp(handler)
        val http = HttpClient(recording.engine)
        clients += http
        return WeatherClient(httpClient = http) to recording
    }

    private fun forecast(
        code: Int,
        prob: Int,
        date: String = "2026-11-17",
    ) = """{"latitude":52.5,"longitude":13.4,"daily":{"time":["$date"],"weather_code":[$code],"precipitation_probability_max":[$prob]}}"""

    private val geocodeBerlin =
        """{"results":[{"latitude":52.52,"longitude":13.405,"name":"Berlin"}]}"""

    @Test
    fun `lat,lon skips geocoding and maps a clear day to dry`() = runTest {
        val (client, http) = client { request ->
            if (request.url.encodedPath == "/v1/forecast") respondJson(forecast(code = 0, prob = 10))
            else unexpected(request)
        }

        val day = client.today("52.52, 13.405")

        assertEquals(WeatherDay(LocalDate.of(2026, 11, 17), dry = true), day)
        assertEquals(1, http.requests.size)
        val url = http.requests.single().url.toString()
        assertTrue(url.contains("latitude=52.52"))
        assertTrue(url.contains("longitude=13.405"))
        assertTrue(url.contains("daily=weather_code%2Cprecipitation_probability_max") || url.contains("daily=weather_code,precipitation_probability_max"))
        assertTrue(url.contains("forecast_days=1"))
    }

    @Test
    fun `a city name is geocoded once and the answer is cached for the process`() = runTest {
        val (client, http) = client { request ->
            when (request.url.encodedPath) {
                "/v1/search" -> respondJson(geocodeBerlin)
                "/v1/forecast" -> respondJson(forecast(code = 3, prob = 20))
                else -> unexpected(request)
            }
        }

        assertEquals(WeatherDay(LocalDate.of(2026, 11, 17), dry = true), client.today("Berlin"))
        assertEquals(WeatherDay(LocalDate.of(2026, 11, 17), dry = true), client.today("Berlin"))

        assertEquals(1, http.requests.count { it.url.encodedPath == "/v1/search" })
        assertEquals(2, http.requests.count { it.url.encodedPath == "/v1/forecast" })
        assertTrue(http.requests.first { it.url.encodedPath == "/v1/search" }.url.toString().contains("name=Berlin"))
    }

    @Test
    fun `a city the geocoding API does not know answers null and makes no forecast call`() = runTest {
        val (client, http) = client { request ->
            if (request.url.encodedPath == "/v1/search") respondJson("""{"results":[]}""")
            else unexpected(request)
        }

        assertNull(client.today("Notarealplace"))
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `a blank location answers null without any request`() = runTest {
        val (client, http) = client { unexpected(it) }

        assertNull(client.today("   "))
        assertNull(client.today(""))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `an HTTP error from the forecast answers null`() = runTest {
        val (client, _) = client { request ->
            if (request.url.encodedPath == "/v1/forecast") {
                respondJson("""{"error":"boom"}""", status = HttpStatusCode.InternalServerError)
            } else unexpected(request)
        }

        assertNull(client.today("52.52,13.405"))
    }

    @Test
    fun `a malformed forecast body answers null`() = runTest {
        val (client, _) = client { request ->
            if (request.url.encodedPath == "/v1/forecast") respondJson("""{"daily":{"time":["2026-11-17"]}}""")
            else unexpected(request)
        }

        assertNull(client.today("52.52,13.405"))
    }

    @Test
    fun `a network failure answers null rather than throwing`() = runTest {
        val (client, _) = client { throw IOException("no route to host") }

        assertNull(client.today("52.52,13.405"))
    }

    @Test
    fun `a failed geocoding lookup is not cached — a later call retries it`() = runTest {
        var calls = 0
        val (client, http) = client { request ->
            when (request.url.encodedPath) {
                "/v1/search" -> {
                    calls++
                    if (calls == 1) throw IOException("offline") else respondJson(geocodeBerlin)
                }
                "/v1/forecast" -> respondJson(forecast(code = 0, prob = 0))
                else -> unexpected(request)
            }
        }

        assertNull(client.today("Berlin"))
        assertEquals(WeatherDay(LocalDate.of(2026, 11, 17), dry = true), client.today("Berlin"))
        assertEquals(2, http.requests.count { it.url.encodedPath == "/v1/search" })
    }

    @Test
    fun `a rain code with a low probability is not dry`() = runTest {
        val (client, _) = client { request ->
            if (request.url.encodedPath == "/v1/forecast") respondJson(forecast(code = 61, prob = 5))
            else unexpected(request)
        }

        assertEquals(WeatherDay(LocalDate.of(2026, 11, 17), dry = false), client.today("52.52,13.405"))
    }

    @Test
    fun `a clear sky at probability 50 is not dry, at 49 it is`() = runTest {
        var prob = 50
        val (client, _) = client { request ->
            if (request.url.encodedPath == "/v1/forecast") respondJson(forecast(code = 0, prob = prob))
            else unexpected(request)
        }

        assertEquals(false, client.today("52.52,13.405")?.dry)
        prob = 49
        assertEquals(true, client.today("52.52,13.405")?.dry)
    }

    @Test
    fun `parseCoordinates accepts lat,lon in any spacing and rejects everything else`() {
        assertEquals(LatLon(52.52, 13.405), parseCoordinates("52.52, 13.405"))
        assertEquals(LatLon(52.52, 13.405), parseCoordinates("52.52,13.405"))
        assertEquals(LatLon(-33.87, 151.21), parseCoordinates(" -33.87 , 151.21 "))
        assertEquals(LatLon(0.0, 0.0), parseCoordinates("0,0"))
        assertNull(parseCoordinates("Berlin"))
        assertNull(parseCoordinates("52.52"))
        assertNull(parseCoordinates("52.52;13.405"))
        assertNull(parseCoordinates("91,10"))
        assertNull(parseCoordinates("10,181"))
        assertNull(parseCoordinates("abc,def"))
        assertNull(parseCoordinates(""))
    }

    @Test
    fun `parseForecastDay needs all three daily values — a partial answer is unknown`() {
        assertNull(parseForecastDay("""{}"""))
        assertNull(parseForecastDay("""{"daily":{}}"""))
        assertNull(parseForecastDay("""{"daily":{"time":["2026-11-17"],"weather_code":[0]}}"""))
        assertNull(parseForecastDay("""{"daily":{"time":["not-a-date"],"weather_code":[0],"precipitation_probability_max":[10]}}"""))
        assertNull(parseForecastDay("not json"))
        assertEquals(
            WeatherDay(LocalDate.of(2026, 11, 17), dry = true),
            parseForecastDay(forecast(code = 45, prob = 49)),
        )
    }

    @Test
    fun `parseGeocode reads the first hit and tolerates a missing results array`() {
        assertEquals(LatLon(52.52, 13.405), parseGeocode(geocodeBerlin))
        assertNull(parseGeocode("""{"results":[]}"""))
        assertNull(parseGeocode("""{}"""))
        assertNull(parseGeocode("not json"))
    }
}
