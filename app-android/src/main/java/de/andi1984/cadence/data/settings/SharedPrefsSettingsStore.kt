package de.andi1984.cadence.data.settings

import android.content.Context
import de.andi1984.cadence.ui.settings.CadenceSettings
import de.andi1984.cadence.ui.settings.Density
import de.andi1984.cadence.ui.settings.SettingsStore
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.settings.ThemeChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android's answer to [SettingsStore]: `SharedPreferences`, not DataStore — the whole file is
 *  a handful of scalars, read once at startup and written on every change. */
class SharedPrefsSettingsStore(context: Context) : SettingsStore {

    private val prefs = context.applicationContext
        .getSharedPreferences("cadence-settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    override val state: StateFlow<CadenceSettings> = _state.asStateFlow()

    private fun load(): CadenceSettings = CadenceSettings(
        theme = prefs.getString(KEY_THEME, null)
            ?.let { name -> ThemeChoice.entries.firstOrNull { it.name == name } }
            ?: ThemeChoice.SYSTEM,
        density = prefs.getString(KEY_DENSITY, null)
            ?.let { name -> Density.entries.firstOrNull { it.name == name } }
            ?: Density.COMFORTABLE,
        sortMode = prefs.getString(KEY_SORT, null)
            ?.let { name -> SortMode.entries.firstOrNull { it.name == name } }
            ?: SortMode.IMPORTANCE,
        showCompleted = prefs.getBoolean(KEY_SHOW_COMPLETED, true),
        // On by default here and off on the desktop: this is the device people carry, so it is
        // the one that should speak up when a task comes due (ADR 0002, decision 9).
        remindersEnabled = prefs.getBoolean(KEY_REMINDERS, true),
        // SharedPreferences has no list type, so the minutes are packed comma-separated —
        // TagIdsCodec's shape, for the same reason: it is one scalar-shaped preference, not a
        // table. Empty by default, same as CadenceSettings itself.
        reminderLeadMinutes = prefs.getString(KEY_REMINDER_LEAD_MINUTES, null)
            ?.split(",")
            ?.mapNotNull { it.toIntOrNull() }
            ?: emptyList(),
        // Off by default on both shells: the weather call is the one non-sync network traffic
        // the app can make, so it waits for an explicit opt-in (ADR 0007, decision 4).
        weatherEnabled = prefs.getBoolean(KEY_WEATHER_ENABLED, false),
        weatherLocation = prefs.getString(KEY_WEATHER_LOCATION, "") ?: "",
    )

    private fun persist(settings: CadenceSettings) {
        prefs.edit()
            .putString(KEY_THEME, settings.theme.name)
            .putString(KEY_DENSITY, settings.density.name)
            .putString(KEY_SORT, settings.sortMode.name)
            .putBoolean(KEY_SHOW_COMPLETED, settings.showCompleted)
            .putBoolean(KEY_REMINDERS, settings.remindersEnabled)
            .putString(KEY_REMINDER_LEAD_MINUTES, settings.reminderLeadMinutes.joinToString(","))
            .putBoolean(KEY_WEATHER_ENABLED, settings.weatherEnabled)
            .putString(KEY_WEATHER_LOCATION, settings.weatherLocation)
            .apply()
        _state.value = settings
    }

    override fun setTheme(theme: ThemeChoice) = persist(_state.value.copy(theme = theme))

    override fun setDensity(density: Density) = persist(_state.value.copy(density = density))

    override fun setSortMode(sortMode: SortMode) = persist(_state.value.copy(sortMode = sortMode))

    override fun setShowCompleted(show: Boolean) = persist(_state.value.copy(showCompleted = show))

    override fun setRemindersEnabled(enabled: Boolean) =
        persist(_state.value.copy(remindersEnabled = enabled))

    override fun setReminderLeadMinutes(minutes: List<Int>) =
        persist(_state.value.copy(reminderLeadMinutes = minutes))

    override fun setWeatherEnabled(enabled: Boolean) =
        persist(_state.value.copy(weatherEnabled = enabled))

    override fun setWeatherLocation(location: String) =
        persist(_state.value.copy(weatherLocation = location))

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_DENSITY = "density"
        const val KEY_SORT = "sort"
        const val KEY_SHOW_COMPLETED = "showCompleted"
        const val KEY_REMINDERS = "remindersEnabled"
        const val KEY_REMINDER_LEAD_MINUTES = "reminderLeadMinutes"
        const val KEY_WEATHER_ENABLED = "weatherEnabled"
        const val KEY_WEATHER_LOCATION = "weatherLocation"
    }
}
