---
title: "Plan: an outdoor section on Today when the weather is good"
description: "Coarse weather data (one dry/raining flag for today, Open-Meteo, no API key) drives an extra Today section listing open @outdoor tasks — only on days that are not raining."
---
*Written 2026-11-17. Nothing here is built yet; when work starts, the decisions move into an ADR
(next free number) and the steps into issues. Tracked in
[#240](https://github.com/viberfasend/primico/issues/240).*

## The feature, in one paragraph

Tasks can be labelled **outdoor** (a well-known `@outdoor` tag, see below). When today's weather
is *good* — coarsely: **not raining** — the Today screen shows an extra section, below the
due-today band, listing the open outdoor tasks that are not already on today's plate. Raining,
unknown weather, or the feature switched off: no section. The weather itself is one boolean per
day, fetched from a free no-key API (Open-Meteo) for a location the user sets once. No hourly
forecasts, no temperature, no icons beyond the section heading — "coarse" is the requirement, not
a limitation to apologise for.

## Decisions

### 1. "Outdoor" is a tag, not a column — and a well-known one

Tags already are the app's cross-cutting label (ADR 0004): packed on the task, synced, backed up,
creatable from the quick-add line with `@outdoor`, filterable from a chip. A dedicated
`outdoor: Boolean` column on `task` would need a schema migration, a new synced column, backup
codec changes and tombstone handling — the whole ADR 0006 machinery — to say less than a tag does.

What a tag does not give us is a *stable* way for the section to find outdoor tasks: a tag is found
by name, and names are user-renameable and localisable. So the repository seeds **one well-known
tag with a fixed id** (`WellKnownTags.OUTDOOR`, a constant UUID, name `outdoor`, a distinct
colour) on first run if no tag with that id exists — tombstone included, so deleting the tag is
respected and never resurrected. Because the id is fixed, renaming the tag to "Draußen" keeps the
section working (`@draußen` in quick-add then matches it, via `Tag.handle`), and sync carries it
like any other row. The outdoor section filters `task.tagIds.contains(WellKnownTags.OUTDOOR)`.

### 2. Coarse weather = one boolean for today, from Open-Meteo, no API key

- **Provider: Open-Meteo** (`api.open-meteo.com`). Free, no key, HTTPS+JSON — and per ADR 0002
  decision 7, HTTPS+JSON is the same on Android and the desktop, so the client is a plain class
  in `:core` (`data/weather/WeatherClient.kt`), next to `CadenceSyncEngine`, constructed with the
  same `defaultHttpClient()` recipe. No new port for the HTTP itself.
- **The request is deliberately tiny:** `GET /v1/forecast?latitude=…&longitude=…&daily=weather_code,precipitation_probability_max&forecast_days=1&timezone=auto`.
  Two daily values in, one boolean out.
- **The coarse rule lives in one pure function** in `:core`
  (`domain/weather/Weather.kt`, e.g. `fun isDryDay(weatherCode: Int, precipProbMax: Int): Boolean`):
  dry = WMO `weather_code` in {0, 1, 2, 3, 45, 48} (clear to overcast, fog) **and**
  `precipitation_probability_max < 50`. Rain, drizzle, snow, showers and thunderstorm codes all
  fail. Unit-tested exhaustively over the WMO code table — this function is the whole feature's
  judgement, so it gets the whole test budget.
- **Location** is a per-device setting, `CadenceSettings.weatherLocation: String` — a city name
  ("Berlin") or `"lat,lon"`, resolved through Open-Meteo's geocoding endpoint (also key-free) and
  cached in memory. Empty = feature effectively off. This avoids `ACCESS_COARSE_LOCATION` and
  the runtime-permission flow on Android entirely, and behaves identically on the desktop.
  Phase 3 (optional) adds "use my device location" on Android as a second source behind the
  coarse-location permission.
- **Scheduling:** the ViewModel fetches once at start and then about hourly while the app runs
  (daily data does not need more), only when the feature is enabled and a location is set. No
  persistence of the forecast: a coarse daily flag is re-fetchable, and if the fetch fails the
  section simply stays hidden — unknown weather is never shown as good weather. The last known
  value is kept in memory across failures within a session.

### 3. The section is a third band of the Today list

- `CadenceUiState` gains `weather: WeatherUiState` (`data class WeatherUiState(val todayDry:
  Boolean? = null)` — null = unknown or disabled, never "good").
- `todayList` (`ui/TaskLists.kt`) appends a third band, `BandHeading.Outdoor`, **only when
  `weather.todayDry == true`**: open outdoor tasks (`tagIds` contains the well-known id,
  `withoutSupersededOccurrences()`, `showCompleted` respected like the due-today band) that are
  *not* already in the overdue or due-today bands — the section is "what else you could do
  outside while it's nice", not a duplicate of the day. Sorted with `settings.sortMode`, like
  every band. Because `taskList(view, today)` is already the single entry point and reads state,
  the new band is testable in `jvmSharedTest` without a Compose runtime.
- `TodayScreen` draws the band after the due-today rows, under a `SectionHeader` with a sun icon
  and a heading string (`today_section_outdoor`), plus the count. The band's rows get no drop
  carets: it is a derived view, and a drag there would reorder `LooseTasks` ambiguously.
- All user-visible strings go to `composeResources` (`values/strings.xml` and
  `values-de/strings.xml`): section heading, settings labels, empty-weather states. No prose in
  Kotlin (the project rule).

### 4. Settings and the network promise

- `CadenceSettings` gains `weatherEnabled: Boolean = false` (opt-in) and
  `weatherLocation: String = ""`, with `SettingsStore` setters, a "Weather" block on the Settings
  screen (toggle + location text field with supporting text), per-device and out of sync like
  theme and density (ADR 0001 decision 9).
- **Privacy note that must ship with it:** the manifest currently promises "signed out there is
  no network traffic at all". With weather enabled, the app calls Open-Meteo (and its geocoding
  endpoint) with the configured city/coordinates — that is the first non-Neon network call, and it
  is off by default. The manifest comment, `docs/concepts/local-first.md` and the site's privacy
  page get one honest paragraph: what is sent (city name or lat/lon, once an hour), to whom, and
  that it can be switched off. No analytics, still.

## Phases

1. **`:core` weather client + domain.** `domain/weather/Weather.kt` (`WeatherDay`,
   `isDryDay(weatherCode, precipProbMax)` over the WMO table) and `data/weather/WeatherClient.kt`
   (Ktor, Open-Meteo forecast + geocoding, in-memory cache of the resolved location). Pure
   parsing functions kept separate from the HTTP so `jvmTest` covers them with a mock engine
   (the `CadenceSyncRoundTest` pattern). Tests: the full WMO code table against `isDryDay`,
   response parsing, geocode resolution.
2. **Well-known outdoor tag.** `WellKnownTags.OUTDOOR` constant + repository seeding (only if the
   id is absent, tombstone included). Tests: seeded once, not re-seeded after delete, rename keeps
   the id. `@outdoor` in quick-add then just works via `matchingHandle`.
3. **Settings + wiring.** `weatherEnabled` / `weatherLocation` in `CadenceSettings` +
   `SettingsStore` (both shells' stores) + Settings screen block + EN/DE strings. `WeatherGateway`
   port in `ui/platform/Ports.kt` (`suspend fun today(location: String): WeatherDay?`), implemented
   by both shells over the `:core` client, faked in `jvmSharedTest`'s `Fakes.kt`. ViewModel: a
   polling coroutine in its scope (start + hourly) that emits `WeatherUiState` into
   `CadenceUiState`.
4. **The section.** `WeatherUiState` on `CadenceUiState`, `BandHeading.Outdoor`, the third band in
   `todayList`, the block in `TodayScreen`, strings. Tests in `CadenceUiStateTest`: band appears
   only when `todayDry == true`, only with open outdoor tasks, excludes done tasks and tasks
   already in the day's bands, respects sort; ViewModel test with the fake gateway for
   enabled/disabled/location-blank/fetch-failure.
5. **Docs.** Manifest comment, `docs/concepts/local-first.md`, the site privacy page (one
   paragraph on the Open-Meteo call), and this plan's decisions move into an ADR when work starts.
6. **Optional, later:** device location on Android behind `ACCESS_COARSE_LOCATION` (runtime
   permission, coarse accuracy is enough — the API is queried daily), and an outdoor line on the
   Today widget. Both are deliberately out of the first cut.

## Deliberately not in this feature

- Hourly forecasts, temperature, wind, UV — "coarse" means one dry/raining flag for today.
- A per-task "outdoor" checkbox or a schema column (decision 1).
- Weather on widgets, on Upcoming, or a forecast screen.
- Location permissions in phase 1; the manual city keeps the first cut permission-free and
  identical on both platforms.
