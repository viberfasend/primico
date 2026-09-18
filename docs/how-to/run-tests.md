---
title: Run the tests
description: The one command CI runs, how to narrow it to a class or a method, which task covers what, and the tests that live outside Gradle.
sidebar:
  order: 1
---

Run the whole suite before you push, run one class while you iterate, and know which of the
six Gradle tasks is the one that would have caught your change.

## Prerequisites

- A clone and **JDK 17 or newer** (see the [quickstart](../tutorials/quickstart.md)).
- The **Android SDK** for the two `testDebugUnitTest` tasks. The JVM tasks don't need it.
- **docker** for the server-side test, a **device or emulator** for the instrumented test, and
  **Python 3.9+** for the Todoist converter's self-test. You only need each one when you touch
  that area.

## Run everything CI runs

```bash
./gradlew :core:testDebugUnitTest :ui:testDebugUnitTest \
          :core:jvmTest :ui:jvmTest :app-desktop:test :ui:compileKotlinJvm
```

This is word for word what [`ci.yml`](../../.github/workflows/ci.yml) runs on every pull request
and every push to `main`. Keep it **one invocation**. Configuring this multi-module build costs
more than running its tests, so splitting it into two `./gradlew` calls pays the configuration
cost twice.

The tests take about five seconds. A green run prints only the summary, because the build logs
failed tests and nothing else:

```
BUILD SUCCESSFUL in 41s
```

A failure prints the assertion **and** its full stack trace (`exceptionFormat = FULL` in the root
[`build.gradle.kts`](../../build.gradle.kts)). The HTML reports sit under each module's
`build/reports/tests/<task>/index.html`, for example
`core/build/reports/tests/jvmTest/index.html`.

`:app-android` is missing from the list on purpose. It has no unit tests (storage moved to
`:core`), and asking for its `testDebugUnitTest` would still build its whole debug variant (31
tasks) to run nothing.

## Run one class or one method

Test method names are backticked sentences, so filter with a wildcard:

```bash
# one class
./gradlew :core:jvmTest --tests "de.andi1984.cadence.RecurrenceEngineTest"

# one method, or every method whose name starts the same way
./gradlew :core:jvmTest --tests "*RecurrenceEngineTest.monthly on a fixed day*"

# the same idea in the other modules
./gradlew :ui:jvmTest --tests "de.andi1984.cadence.ui.CadenceUiStateTest"
./gradlew :app-desktop:test --tests "de.andi1984.cadence.desktop.data.DesktopSettingsStoreTest"
```

While you iterate, use the `jvmTest` tasks. They skip the Android compilation and are the
fastest loop.

## Which task covers what

`:core` and `:ui` are Kotlin Multiplatform modules with a JVM and an Android target. Their shared
tests live in one source set, `jvmSharedTest`, which is compiled **twice**: once per target. Each
module also has a small `jvmTest` source set that only the JVM target compiles.

| Task | Compiles and runs | What only this task catches |
|---|---|---|
| `:core:jvmTest` | `core/src/jvmSharedTest` + `core/src/jvmTest` (`CadenceCoreTest`, `DatabaseDriverFactoryTest`) | the desktop JVM target; the desktop migration path via `PRAGMA user_version` |
| `:core:testDebugUnitTest` | `core/src/jvmSharedTest`, compiled for Android | the Android compilation of the domain and storage code |
| `:ui:jvmTest` | `ui/src/jvmSharedTest` + `ui/src/jvmTest` (`CadenceViewModelCrudTest`, `CadenceViewModelUndoTest`) | the ViewModel tests, which run **only** here |
| `:ui:testDebugUnitTest` | `ui/src/jvmSharedTest`, compiled for Android | the Android compilation of `CadenceUiState`, `sortedFor`, `UndoSlot` and the drag model |
| `:app-desktop:test` | `app-desktop/src/test` | `DesktopSettingsStore`, `DesktopWorkspaceStore`, `DesktopReminderScheduler`, `DesktopNavigator` |
| `:ui:compileKotlinJvm` | no tests | that every screen still compiles for the JVM. `assembleDebug` only compiles `:ui`'s Android target, so without this a desktop-only break could stay green |

No composable is tested: that would need the Compose test runtime, and none of the rules worth
pinning live in a composable.

:::note
The CI command does not compile `:app-android` at all. If you changed anything the Android shell
calls (a port, a ViewModel signature, a screen's parameters), also run `./gradlew assembleDebug`.
:::

## The instrumented test: quick-add on a real regex engine

```bash
adb devices                              # a phone or an emulator must be listed
./gradlew connectedDebugAndroidTest
```

There is exactly one instrumented test,
[`QuickAddPatternsDeviceTest`](../../app-android/src/androidTest/java/de/andi1984/cadence/QuickAddPatternsDeviceTest.kt).
It compiles the quick-add grammar with the **device's ICU** regex engine. The unit tests use
`java.util.regex`, and the two disagree: an inline flag ICU rejects passes every JVM test and then
crashes the quick-add sheet on a phone. CI has no emulator, so run it yourself after touching
[`QuickAddPatterns`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/domain/parse/QuickAddPatterns.kt)
or a [`QuickAddLexicon`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/domain/parse/QuickAddLexicon.kt).

## The server half: the Neon migrations

No Gradle task compiles the SQL under `neon/`, so it has its own test:

```bash
bash neon/tests/run.sh
```

It starts a throwaway `postgres:16` container, stubs what the Neon Data API provisions
(`auth.user_id()`, the `authenticated` and `anonymous` roles, **and** Neon's default privileges),
applies every migration twice (once through `migrate.sh`, once directly, to check idempotence),
and checks the stale-write trigger, row-level-security isolation, the client's tombstone sweep,
the bookkeeping lock-down and `neon/db.sh`. Each check prints `ok` or `FAIL`, and a green run ends
with:

```
all checks passed
```

The container is removed pass or fail. Set `CADENCE_PG_IMAGE` to try another Postgres image. Run
the test after any edit under `neon/`.

## The Todoist converter

```bash
python3 tools/todoist_import.py --self-test
```

This covers the converter's date and repeat-phrase parsing. The other half of that contract (that
the app still reads what the script writes) is `:core`'s `TodoistImportFixtureTest`, which the CI
command already runs.

## When the suite hangs instead of failing

Every `Test` task has a **five-minute timeout** (the `subprojects` block in the root
[`build.gradle.kts`](../../build.gradle.kts)). That's two orders of magnitude above what the suite
needs, so hitting it means a hang, and there's one well-known cause.

:::caution[A test that hands a class a scope must use backgroundScope]
`runTest` drains its `TestScheduler` after the test body returns. A `while (true) { … delay(n) }`
loop running on that scheduler (`DesktopReminderScheduler`'s poll, the sync engine's foreground
poll, the ViewModel's collectors) always has one more `delay` queued, so the drain never reaches
idle. Nothing fails: the JVM spins at 100% CPU until the timeout.

A scope of your own plus `@After { scope.cancel() }` does **not** help, because `@After` runs
*after* the drain. `runTest`'s `backgroundScope` runs on the same scheduler and is cancelled
*before* the drain, so it's both the shorter code and the only correct one:

```kotlin
// DesktopReminderSchedulerTest
private fun TestScope.scheduler(): DesktopReminderScheduler =
    DesktopReminderScheduler(
        scope = backgroundScope,
        trayIcon = null,
        notify = { title -> fired += title },
    )
```

`CadenceViewModelUndoTest`, `CadenceViewModelCrudTest` and `DesktopReminderSchedulerTest` all
take their scope this way. A ViewModel test also has to **subscribe** to `state`
(`backgroundScope.launch { viewModel.state.collect { } }`), because `stateIn(WhileSubscribed)`
keeps the upstream cold until something reads it.
:::

## Verify

- The CI command ends in `BUILD SUCCESSFUL`, and ran in seconds of test time, not minutes.
- If you touched `neon/`: `bash neon/tests/run.sh` ends with `all checks passed`.
- If you touched quick-add patterns or a lexicon: `connectedDebugAndroidTest` passes on a device.

## Related

- [Testing philosophy](../concepts/testing-philosophy.md): why no store is ever faked, and what
  `Fakes.kt` does fake.
- [Build and CI reference](../reference/build-and-ci.md): every task and workflow.
- [Your first contribution](../tutorials/first-contribution.md): writing a test from scratch.
