package de.andi1984.cadence.desktop.data

import de.andi1984.cadence.data.weather.WeatherClient
import de.andi1984.cadence.domain.weather.WeatherDay
import de.andi1984.cadence.ui.platform.WeatherGateway

/**
 * The desktop answer to [WeatherGateway]: `:core`'s [WeatherClient] as it is — HTTPS+JSON is not a
 * platform difference (ADR 0002, decision 7), so this is the same four lines Android writes. The
 * port exists so the ViewModel's tests can fake the call instead of making it.
 */
class DesktopWeatherGateway(
    private val client: WeatherClient = WeatherClient(),
) : WeatherGateway {

    override suspend fun today(location: String): WeatherDay? = client.today(location)
}
