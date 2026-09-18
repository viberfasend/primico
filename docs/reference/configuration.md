---
title: Configuration
description: Build-time environment variables, CI secrets, every per-device setting with its default on each platform, where each file lives, the fixed identities, and the Android permissions.
sidebar:
  order: 8
---

Primico has no configuration file of its own and no runtime flags. What varies is decided at
**build time** by environment variables, and **per device** by the Settings screen. Everything
still named `CADENCE_*` keeps that name on purpose — see [Naming](../concepts/naming.md).

## Build-time environment

Read from the environment of the Gradle invocation (or of the script named).

| Variable | Read by | Effect | Unset |
|---|---|---|---|
| `CADENCE_NEON_DATA_API_URL` | `:core`'s `generateNeonConfig` task | Compiled into the generated `NeonBuildConfig.DATA_API_URL`; the sync Data API endpoint | no sync (see below) |
| `CADENCE_NEON_AUTH_URL` | `:core`'s `generateNeonConfig` task | Compiled into `NeonBuildConfig.AUTH_URL`; the Neon Auth endpoint | no sync |
| `CADENCE_VERSION_NAME` | [`app-android/build.gradle.kts`](../../app-android/build.gradle.kts) | Android `versionName` | `0.0.0-dev` |
| | [`app-desktop/build.gradle.kts`](../../app-desktop/build.gradle.kts) | Desktop `packageVersion`, with any `-suffix` cut off (jpackage wants plain `major.minor.patch`) | `1.0.0` |
| | [`build.sh`](../../.github/scripts/build.sh) | Used as the version instead of deriving one | derived by `next-version.sh` |
| | desktop `Main.kt`, **at run time** | The version shown in the desktop app's Settings (`AppInfo.version`) | `0.0.0-dev` |
| `CADENCE_VERSION_CODE` | `app-android/build.gradle.kts` | Android `versionCode` | `1` |
| `CADENCE_KEYSTORE` | `app-android/build.gradle.kts`, `check-signing.sh` | Path to the release keystore; when set **and** the file exists, the release APK is signed with it | release APK signed with the committed debug key |
| `CADENCE_KEYSTORE_PASSWORD` | `app-android/build.gradle.kts` | Store password of the release keystore | — |
| `CADENCE_KEY_ALIAS` | `app-android/build.gradle.kts` | Key alias in the release keystore | — |
| `CADENCE_KEY_PASSWORD` | `app-android/build.gradle.kts` | Key password | — |

The two sync URLs are trimmed, are Gradle task inputs (changing one regenerates the file), and
are written to `core/build/generated/neonConfig/kotlin/de/andi1984/cadence/data/sync/NeonBuildConfig.kt`
— generated, never committed. **Both** must be set: with only one, the task logs a warning and the
build carries neither. With neither, `NeonConfig.fromBuild` is `null`, `SyncStatus` is
`Unconfigured` for the life of the process, Settings shows a paragraph instead of the sign-in
form, and no request is ever made. The URL shapes are in [Sync wire format](sync-wire.md#endpoints);
the walkthrough is [Self-hosting](../self-hosting.md).

### Script environment

| Variable | Read by | Meaning | Default |
|---|---|---|---|
| `CADENCE_NEON_DB_URL` | [`neon/migrate.sh`](../../neon/migrate.sh), [`neon/db.sh`](../../neon/db.sh) | Direct Postgres connection string of the Neon project (a secret; never in the app) | required |
| `CADENCE_PG_IMAGE` | [`neon/tests/run.sh`](../../neon/tests/run.sh) | Docker image for the throwaway Postgres | `postgres:16` |
| `CADENCE_RELEASE_KEY_DIR` | [`tools/release-signing-wizard.sh`](../../tools/release-signing-wizard.sh) | Where the release keystore and `release.env` live, outside any checkout | `~/.cadence-release` |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | `build.sh`, `check-signing.sh` | Android SDK; `build.sh` also reads `sdk.dir` from `local.properties` | APK targets skipped (or fail when named) |
| `GITHUB_OUTPUT` | [`next-version.sh`](../../.github/scripts/next-version.sh) | Where the step outputs go | stdout |

## CI secrets

Repository secrets, set by `tools/release-signing-wizard.sh` (with `gh secret set`).

| Secret | Used by | Becomes |
|---|---|---|
| `CADENCE_KEYSTORE_BASE64` | `android.yml`, `release.yml` | decoded to `$RUNNER_TEMP/primico.jks`, exported as `CADENCE_KEYSTORE` |
| `CADENCE_KEYSTORE_PASSWORD` | `android.yml`, `release.yml` | the same-named environment variable |
| `CADENCE_KEY_ALIAS` | `android.yml`, `release.yml` | the same-named environment variable |
| `CADENCE_KEY_PASSWORD` | `android.yml`, `release.yml` | the same-named environment variable |
| `CADENCE_NEON_DATA_API_URL` | `android.yml`, `desktop.yml`, `release.yml` | the same-named environment variable |
| `CADENCE_NEON_AUTH_URL` | `android.yml`, `desktop.yml`, `release.yml` | the same-named environment variable |

`ci.yml` and `pages.yml` use no secrets. Details of each workflow: [Build and CI](build-and-ci.md).

## Per-device settings

The fields of `CadenceSettings` ([Data model](data-model.md#cadencesettings)). Never synced and
never in the task database. `SettingsStore` is the port; Android implements it with
[`SharedPrefsSettingsStore`](../../app-android/src/main/java/de/andi1984/cadence/data/settings/SharedPrefsSettingsStore.kt),
the desktop with [`DesktopSettingsStore`](../../app-desktop/src/main/kotlin/de/andi1984/cadence/desktop/data/DesktopSettingsStore.kt).

| Setting | Values | Android key | Android default | Desktop key | Desktop default | In backup file |
|---|---|---|---|---|---|---|
| Theme | `SYSTEM`, `LIGHT`, `DARK` | `theme` | `SYSTEM` | `theme` | `SYSTEM` | yes |
| Density | `COMFORTABLE`, `COMPACT` | `density` | `COMFORTABLE` | `density` | `COMFORTABLE` | yes |
| Sort mode | `IMPORTANCE`, `DATE`, `MANUAL` | `sort` | `IMPORTANCE` | `sortMode` | `IMPORTANCE` | yes |
| Show completed | boolean | `showCompleted` | `true` | `showCompleted` | `true` | yes |
| Reminders on this device | boolean | `remindersEnabled` | **`true`** | `remindersEnabled` | **`false`** | no |
| Notify me before (minutes) | list of integers | `reminderLeadMinutes`, comma-separated string | empty | `reminderLeadMinutes`, JSON array | empty | no |

An unknown or unreadable value falls back to the default. The app **language** is not a setting:
Android uses the per-app language picker (`res/xml/locales_config.xml`); the desktop uses
`Locale.getDefault()`.

A desktop `settings.json`:

```json
{"theme":"DARK","density":"COMPACT","sortMode":"MANUAL","showCompleted":true,"remindersEnabled":true,"reminderLeadMinutes":[15,5]}
```

It is rewritten whole on every change through `settings.json.tmp` and a rename, off the UI thread;
`AppContainer.shutdown()` flushes pending writes before the process exits.

### Desktop window state

Kept apart from the settings in `workspace.json`
([`DesktopWorkspaceStore`](../../app-desktop/src/main/kotlin/de/andi1984/cadence/desktop/data/DesktopWorkspaceStore.kt)),
because none of it means anything on a phone. Keys and defaults: `sidebarWidth` (`268`, clamped
200–420), `sidebarCollapsed` (`false`), `collapsedProjects` (`[]`), `windowWidth` (`1180`, at least
640), `windowHeight` (`820`, at least 480), `windowX`/`windowY` (`null`), `maximized` (`false`).
Window bounds are written a second after a move or resize, and again on close.

## Where files live

| File | Android | Linux | macOS | Windows |
|---|---|---|---|---|
| Data directory | app-private storage | `$XDG_DATA_HOME/primico`, default `~/.local/share/primico` | `~/Library/Application Support/Primico` | `%APPDATA%\Primico`, default `~\AppData\Roaming\Primico` |
| Database | `databases/cadence.db` | `<data>/cadence.db` | `<data>/cadence.db` | `<data>\cadence.db` |
| Settings | `shared_prefs/cadence-settings.xml` | `<data>/settings.json` | `<data>/settings.json` | `<data>\settings.json` |
| Window state | — | `<data>/workspace.json` | `<data>/workspace.json` | `<data>\workspace.json` |
| Attachment blobs | `files/attachments/` | `<data>/attachments/` | `<data>/attachments/` | `<data>\attachments\` |
| Blob staging | `files/attachments-tmp/` | `<data>/attachments-tmp/` | `<data>/attachments-tmp/` | `<data>\attachments-tmp\` |

The desktop data directory comes from
[`PlatformDirs.dataDir()`](../../app-desktop/src/main/kotlin/de/andi1984/cadence/desktop/platform/PlatformDirs.kt),
which renames a legacy `cadence`/`Cadence` directory in place on first start when the new one does
not exist yet. The sync session and cursors live **in the database** (`syncStateRow`), not in the
settings file.

## Fixed identities

These are persisted identities or published contracts and do not change with the product name.

| Identity | Value | Where |
|---|---|---|
| Android application id | `de.andi1984.cadence` (release), `de.andi1984.cadence.debug` (debug, `versionNameSuffix` `-debug`) | `app-android/build.gradle.kts` |
| Android namespaces | `de.andi1984.cadence`, `de.andi1984.cadence.core`, `de.andi1984.cadence.ui` | the three Android build files |
| Desktop main class | `de.andi1984.cadence.desktop.MainKt` | `app-desktop/build.gradle.kts` |
| Desktop package name | `Primico` (Linux package `primico`) | same |
| macOS bundle id | `de.andi1984.cadence` | same |
| Windows upgrade UUID | `8f2b9c3e-6a3f-4b8a-9b7a-1e6f2c9d4a01` | same |
| Database file | `cadence.db` | `CADENCE_DATABASE_FILE_NAME` |
| Backup format string | `cadence.backup` | `BackupCodec.FORMAT` |
| Attachment `FileProvider` authority | `${applicationId}.attachments` | `AndroidManifest.xml` |
| Notification channel | `cadence-reminders` | `AlarmReminderScheduler.CHANNEL_ID` |

## Android permissions

From [`AndroidManifest.xml`](../../app-android/src/main/AndroidManifest.xml). There is no storage
permission: backups and attachments go through the Storage Access Framework, where the user picks
the file.

| Permission | Why |
|---|---|
| `INTERNET` | Sync with Neon. Signed out, no request is made. |
| `ACCESS_NETWORK_STATE` | Listed with `INTERNET` for sync; `SyncWorker` runs only with a connected network. |
| `POST_NOTIFICATIONS` | Reminder notifications (a runtime permission on Android 13+). |
| `RECEIVE_BOOT_COMPLETED` | `BootReceiver` re-arms reminders after a reboot, honouring the reminders switch. |
| `SCHEDULE_EXACT_ALARM` (`maxSdkVersion="32"`) | Exact reminder alarms on Android 12/12L, where it is granted by default. |
| `USE_EXACT_ALARM` | Exact reminder alarms on Android 13+, granted at install with no prompt. |

Exact alarms use `setExactAndAllowWhileIdle`, falling back to `setAndAllowWhileIdle` when
`canScheduleExactAlarms()` reports the permission revoked. See [Reminders](../concepts/reminders.md).

Also in the manifest: `allowBackup="true"` with `data_extraction_rules.xml` (Android 12+) and
`backup_rules.xml` (older) — the database and shared preferences are backed up, attachment blobs
are not; `localeConfig` for the per-app language picker; and the home-screen widget receivers.

## Related

- [Build and CI](build-and-ci.md)
- [Database](database.md#where-the-file-lives)
- [Self-hosting](../self-hosting.md)
