package de.andi1984.cadence.data.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

/**
 * The one HTTP recipe every network client in `:core` shares — sync ([de.andi1984.cadence.data.sync.CadenceSyncEngine])
 * and weather ([de.andi1984.cadence.data.weather.WeatherClient]) alike. OkHttp is the engine that runs on
 * Android and the desktop both, so there is no per-platform engine to pick, and the timeouts are
 * the ones that have to read as latency rather than failure: a remote service that scaled to zero
 * answers its first request only after a cold start, and that is not an OFFLINE.
 *
 * Generous on purpose: 10s to connect, 30s per request and per socket read.
 */
internal fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
}
