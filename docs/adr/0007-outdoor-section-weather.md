---
title: "ADR 0007 — An outdoor section on Today, driven by one coarse weather flag"
sidebar:
  label: "0007 · Outdoor weather"
  order: 7
---
**Status:** accepted · **Date:** 2026-11-17 · **Supersedes nothing.** Builds on
[ADR 0002](0002-supabase-sync.md) decision 7 (HTTPS+JSON is not a platform difference, so the
client lives in `:core`) and [ADR 0004](0004-tags.md) (a cross-cutting label is a tag).
Planned in [#240](https://github.com/viberfasend/primico/issues/240)
([plan](../plans/outdoor-tasks-weather.md)).

## Context

Tasks that can be done outside — mowing, a bike ride, the terrace — want a place to surface on a
day they can actually happen. The requirement is deliberately coarse: one dry/raining flag for
today, no hourly forecast, no temperature, no icons beyond the section heading. The feature must
also be permission-free (no device location), identical on Android and the desktop, and it must
not weaken the local-first promise: signed out and switched off, the app makes no network
traffic at all.

## Decisions

### 1. "Outdoor" is a well-known tag with a fixed id, not a column

A tag already is the app's cross-cutting label (ADR 0004): packed on the task, synced, backed up,
creatable from quick-add with `@outdoor`, reachable from a chip and from search. A dedicated
`outdoor: Boolean` column would need a schema migration, a new synced column, backup codec changes
and tombstone handling to say less than a tag does.

What a tag does not give is a *stable* handle for the section: names are user-renameable. So the
repository seeds one well-known tag with a **fixed id** (`WellKnownTags.OUTDOOR`, a name-based UUID
every device computes identically) on first start, if no row with that id exists — tombstone
included, so deleting the tag is respected and never resurrected. Renaming it to "Draußen" keeps
the section working, and `@outdoor` in quick-add reaches it through the existing `matchingHandle`.
The outdoor section filters `task.tagIds.contains(WellKnownTags.OUTDOOR)`. No schema change: tags
are packed on the task (ADR 0004).

### 2. Coarse weather is one boolean per day, from Open-Meteo, no API key

- **Provider: Open-Meteo** (`api.open-meteo.com`). Free, no key, HTTPS+JSON — so per ADR 0002
  decision 7 the client is a plain class in `:core` (`data/weather/WeatherClient.kt`), next to
  `CadenceSyncEngine`, on the same shared `defaultHttpClient()` recipe. No new port for the HTTP
  itself; the `WeatherGateway` port in `:ui` exists only so the ViewModel's tests can fake the
  call.
- **The request is deliberately tiny:**
  `GET /v1/forecast?latitude=…&longitude=…&daily=weather_code,precipitation_probability_max&forecast_days=1&timezone=auto`.
  Two daily values in, one boolean out. A city name is resolved once through the key-free
  geocoding endpoint and cached in memory for the process; `"lat,lon"` skips geocoding entirely.
- **The judgement is one pure function** in `:core` (`domain/weather/Weather.kt`,
  `isDryDay(weatherCode, precipProbMax)`): dry = WMO `weather_code` in {0, 1, 2, 3, 45, 48} (clear
  to overcast, fog) **and** `precipitation_probability_max < 50`. Rain, drizzle, snow, showers,
  thunderstorms and unrecognised codes all fail. Unit-tested exhaustively over the WMO table.

### 3. Scheduling: fetch at start and about hourly, nothing persisted, failure never means "good"

The ViewModel fetches once at start and then about hourly while the app runs — daily data does not
need more — only when the feature is enabled and a location is set. The settings flow and an
hourly ticker are combined, so changing the setting in Settings acts immediately rather than at
the next tick. No forecast is persisted: a coarse daily flag is re-fetchable, and the last known
value is kept in memory across failures within a session. If the fetch fails, the section stays
hidden — **unknown weather is never shown as good weather** (`WeatherUiState.todayDry` is null
unless a fetch actually answered).

### 4. Settings are per-device and opt-in; the section is a third band of Today

`CadenceSettings` gains `weatherEnabled` (default **off**) and `weatherLocation` (a city name or
`"lat,lon"`, empty = off), with `SettingsStore` setters on both shells and a Weather block on the
Settings screen — per-device and out of sync like theme and density (ADR 0001, decision 9). The
manual location keeps the first cut permission-free and identical on both platforms; device
location behind `ACCESS_COARSE_LOCATION` is a later optional phase.

`todayList` appends a third band, `BandHeading.Outdoor`, only when `weather.todayDry == true`:
open outdoor tasks (usual sort, `showCompleted` respected, superseded occurrences dropped) that are
not already in the overdue or due-today bands — "what else you could do outside while it's nice",
not a duplicate of the day. Raining, unknown weather or the feature switched off: no section. The
band's rows get no drop carets; it is a derived view.

**The privacy note that ships with it:** the manifest's "signed out there is no network traffic
at all" comment, `docs/concepts/local-first.md` and the site's data section each say plainly that
with the feature on, the app calls Open-Meteo (and its geocoding endpoint) with the configured
city/coordinates, about once an hour, and that the setting is off by default. No analytics, still.

## Consequences

- The first non-Neon network call exists, and it is opt-in, off by default, and sends exactly one
  string (the location) to one free API. Every doc that promised "no network traffic signed out"
  now says "except this opt-in one".
- "Good weather" is a pure function of two numbers, pinned by an exhaustive test over the WMO
  table — changing the threshold or the code set is a one-line diff with the tests naming exactly
  what moved.
- The well-known tag means a feature can find its rows by id; a second well-known tag later costs
  one constant and one seed line, not a migration.
- Nothing about the forecast is stored or synced, so there is nothing to leak, merge or tombstone.

## Alternatives rejected

- **A dedicated `outdoor: Boolean` column on the task.** Needs a schema migration, a new synced
  column, backup codec changes and tombstone handling (the whole ADR 0006 machinery) to say less
  than a tag; and it would still need a fixed id to be stable across renames, which is what the
  well-known tag already is.
- **Device location (coarse) as the source.** Would need `ACCESS_COARSE_LOCATION` and the runtime
  permission flow on Android, and would differ from the desktop. The manual city keeps the first
  cut permission-free and identical everywhere; it can be added later as a second source.
- **A richer forecast (hourly, temperature, icons).** "Coarse" is the requirement, not a limitation:
  one dry/raining flag per day is what the section asks, and every extra value would be fetched,
  stored and drawn for nothing.
- **Persisting the last forecast.** A daily flag is re-fetchable; keeping it would add a store and
  a sync/backup question for a value that is stale the next morning anyway.
- **Weather on widgets, Upcoming or a forecast screen.** Deliberately out of the first cut —
  Today is where "it's nice out, what could I do outside" belongs.
