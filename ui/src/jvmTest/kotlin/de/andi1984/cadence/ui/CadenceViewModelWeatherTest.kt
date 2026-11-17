package de.andi1984.cadence.ui

import de.andi1984.cadence.data.sync.CadenceSyncEngine
import de.andi1984.cadence.domain.weather.WeatherDay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The weather poll the ViewModel runs for Today's outdoor section, against a [FakeWeatherGateway]:
 * fetched at start and about hourly, only while the feature is on and a location is set, reset
 * when it is switched off, and a failed fetch keeping the last known value — unknown is never
 * shown as a good-weather day. Same arrangement as [CadenceViewModelCrudTest]: a real repository
 * over in-memory SQLite, only the platform ports faked.
 */
class CadenceViewModelWeatherTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 17)

    private val stores = TestStores()
    private val repository = stores.repository()
    private val reminders = RecordingReminderScheduler()
    private val settings = FakeSettingsStore()
    private val weatherGateway = FakeWeatherGateway()

    private fun TestScope.viewModel(): CadenceViewModel {
        val viewModel = CadenceViewModel(
            repository = repository,
            settingsStore = settings,
            reminderScheduler = reminders,
            backupGateway = FakeBackupGateway(),
            attachmentOpener = RecordingAttachmentOpener(),
            weatherGateway = weatherGateway,
            syncEngine = CadenceSyncEngine(FakeSyncStore(), backgroundScope),
            scope = backgroundScope,
        )
        backgroundScope.launch { viewModel.state.collect { } }
        runCurrent()
        return viewModel
    }

    @Test
    fun `the weather stays unknown while the feature is off, and the gateway is never asked`() = runTest {
        val viewModel = viewModel()

        assertNull(viewModel.state.value.weather.todayDry)
        assertTrue(weatherGateway.locations.isEmpty())
    }

    @Test
    fun `enabled without a location asks nothing`() = runTest {
        settings.setWeatherEnabled(true)
        val viewModel = viewModel()

        assertNull(viewModel.state.value.weather.todayDry)
        assertTrue(weatherGateway.locations.isEmpty())
    }

    @Test
    fun `enabled with a location fetches today once at start`() = runTest {
        weatherGateway.result = WeatherDay(today, dry = true)
        settings.setWeatherEnabled(true)
        settings.setWeatherLocation("Berlin")
        val viewModel = viewModel()

        assertEquals(true, viewModel.state.value.weather.todayDry)
        assertEquals(listOf("Berlin"), weatherGateway.locations)
    }

    @Test
    fun `a failed fetch is unknown at first and keeps the last known value afterwards`() = runTest {
        weatherGateway.result = null
        settings.setWeatherEnabled(true)
        settings.setWeatherLocation("Berlin")
        val viewModel = viewModel()

        // Unknown, not "good": a failure never becomes a dry day.
        assertNull(viewModel.state.value.weather.todayDry)

        weatherGateway.result = WeatherDay(today, dry = true)
        advanceTimeBy(CadenceViewModel.WEATHER_REFRESH_INTERVAL.toMillis() + 1)
        runCurrent()
        assertEquals(true, viewModel.state.value.weather.todayDry)

        weatherGateway.result = null
        advanceTimeBy(CadenceViewModel.WEATHER_REFRESH_INTERVAL.toMillis() + 1)
        runCurrent()
        assertEquals(true, viewModel.state.value.weather.todayDry)
        assertEquals(3, weatherGateway.locations.size)
    }

    @Test
    fun `it refreshes about hourly while the app runs`() = runTest {
        weatherGateway.result = WeatherDay(today, dry = false)
        settings.setWeatherEnabled(true)
        settings.setWeatherLocation("52.52,13.405")
        val viewModel = viewModel()

        assertEquals(false, viewModel.state.value.weather.todayDry)
        assertEquals(1, weatherGateway.locations.size)

        advanceTimeBy(CadenceViewModel.WEATHER_REFRESH_INTERVAL.toMillis() + 1)
        runCurrent()
        assertEquals(2, weatherGateway.locations.size)
        assertEquals(listOf("52.52,13.405", "52.52,13.405"), weatherGateway.locations)
    }

    @Test
    fun `turning the feature off clears the section and stops the poll`() = runTest {
        weatherGateway.result = WeatherDay(today, dry = true)
        settings.setWeatherEnabled(true)
        settings.setWeatherLocation("Berlin")
        val viewModel = viewModel()
        assertEquals(true, viewModel.state.value.weather.todayDry)

        settings.setWeatherEnabled(false)
        runCurrent()

        assertNull(viewModel.state.value.weather.todayDry)
        advanceTimeBy(3 * CadenceViewModel.WEATHER_REFRESH_INTERVAL.toMillis())
        runCurrent()
        assertEquals(1, weatherGateway.locations.size)
    }

    @Test
    fun `changing the location re-asks with the new one`() = runTest {
        weatherGateway.result = WeatherDay(today, dry = true)
        settings.setWeatherEnabled(true)
        settings.setWeatherLocation("Berlin")
        val viewModel = viewModel()

        settings.setWeatherLocation("52.52,13.405")
        runCurrent()

        assertEquals(listOf("Berlin", "52.52,13.405"), weatherGateway.locations)
    }
}
