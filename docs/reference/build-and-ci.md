---
title: Build and CI
description: The Gradle tasks, build.sh's targets and flags, the five GitHub workflows with their triggers and inputs, artefact names, version derivation and signing.
sidebar:
  order: 9
---

One script, [`.github/scripts/build.sh`](../../.github/scripts/build.sh), is the build. The
workflows only call it, and a laptop runs the same script, so a release can be cut entirely by
hand. The step-by-step for a release is [Cut a release](../how-to/cut-a-release.md); running the
tests is [Run the tests](../how-to/run-tests.md).

Requirements: JDK 17 or newer (bytecode targets 17), and for APKs an Android SDK. Gradle runs with
`org.gradle.parallel=true`, `org.gradle.caching=true` and `-Xmx3g`
([`gradle.properties`](../../gradle.properties)). Every `Test` task has a **five-minute
timeout** and prints full stack traces on failure (root
[`build.gradle.kts`](../../build.gradle.kts)); a hang fails instead of running out a job.

## Gradle tasks

| Task | What it does |
|---|---|
| `./gradlew :core:testDebugUnitTest :ui:testDebugUnitTest :core:jvmTest :ui:jvmTest :app-desktop:test :ui:compileKotlinJvm` | The whole test suite, as CI runs it — one invocation, because configuring the build costs more than running it |
| `:core:jvmTest`, `:ui:jvmTest` | `jvmSharedTest` + `jvmTest` compiled for the desktop JVM |
| `:core:testDebugUnitTest`, `:ui:testDebugUnitTest` | `jvmSharedTest` compiled for Android, run on the host JVM |
| `:app-desktop:test` | The desktop shell's tests (`src/test/kotlin`) |
| `:ui:compileKotlinJvm` | Compiles every screen for the JVM — `assembleDebug` only compiles `:ui`'s Android target |
| `./gradlew :core:jvmTest --tests "de.andi1984.cadence.RecurrenceEngineTest"` | One test class (method names are backticked sentences: `--tests "*RecurrenceEngineTest.monthly on a fixed day*"`) |
| `./gradlew connectedDebugAndroidTest` | The one instrumented test, `QuickAddPatternsDeviceTest`; needs a device, CI has no emulator |
| `./gradlew assembleDebug` | `app-android/build/outputs/apk/debug/app-android-debug.apk` |
| `./gradlew assembleRelease` | Release APK, signed with `CADENCE_KEYSTORE` or else the committed debug key |
| `./gradlew :app-desktop:run` | Launch the desktop app from source |
| `./gradlew :app-desktop:packageDistributionForCurrentOS` | The native installers for this OS |
| `./gradlew :app-desktop:packageDeb` / `packageRpm` / `packageDmg` / `packageMsi` | One installer format |
| `./gradlew :app-desktop:createDistributable` | The app image (`app-desktop/build/compose/binaries/main/app`) that `build.sh tar` packs |
| `./gradlew :app-desktop:packageUberJarForCurrentOS` | A runnable jar, no native packaging tools needed |
| `generateNeonConfig` (`:core`) | Writes `NeonBuildConfig` from the two sync URLs; runs as part of every `:core` compilation |

`:app-android` has no unit tests; a bare `testDebugUnitTest` would still build its whole debug
variant (31 tasks) to run none, which is why every command above names modules. No lint or format
task is wired up.

## build.sh

```bash
bash .github/scripts/build.sh [target …] [option …]
```

### Targets

Default `all`.

| Target | Gradle tasks | Output in `dist/` | Needs |
|---|---|---|---|
| `apk` | `assembleDebug assembleRelease` | `primico-debug.apk`, `primico-release.apk` | Android SDK |
| `deb` | `:app-desktop:packageDeb` | the `.deb` jpackage named | Linux, `fakeroot` |
| `rpm` | `:app-desktop:packageRpm` | the `.rpm` jpackage named | Linux, `rpmbuild` |
| `tar` | `:app-desktop:createDistributable` | `primico-linux-x64.tar.gz` | Linux |
| `dmg` | `:app-desktop:packageDmg` | the `.dmg` jpackage named | macOS, `hdiutil` |
| `msi` | `:app-desktop:packageMsi` | the `.msi` jpackage named | Windows, WiX (`candle`/`light`) |
| `desktop` | every format this OS can build | Linux: `deb rpm tar`; macOS: `dmg`; Windows: `msi` | |
| `all` | `apk` + `desktop` | | |

A format whose tool is missing is **skipped with a warning** when `all` or `desktop` implied it,
and is a **hard error** when it was named outright. Naming a format another OS builds is an
error: jpackage cannot cross-compile.

### Options

| Option | Effect |
|---|---|
| `--version X.Y.Z` | Use this version (must be `major.minor.patch`); also sets the version code |
| `--skip-tests` | Do not run the tests first |
| `--skip-signing-check` | Do not run `check-signing.sh` |
| `--keystore FILE` | Sign the release APK with this keystore (sets `CADENCE_KEYSTORE`; needs `CADENCE_KEYSTORE_PASSWORD`, `CADENCE_KEY_ALIAS`, `CADENCE_KEY_PASSWORD`) |
| `--output DIR` | Stage into `DIR` instead of `dist` |
| `--clean` | Empty the output directory first |
| `--dry-run` | Print targets, version, the Gradle command and output directory, then exit |
| `-h`, `--help` | Usage |

### What it runs

1. **Preflight** — Java ≥ 17, the SDK and packaging tools; drops or refuses formats as above.
2. **Version** — `--version`, else an inherited `CADENCE_VERSION_NAME` (+ `CADENCE_VERSION_CODE`),
   else [`next-version.sh`](../../.github/scripts/next-version.sh). With no commits since the
   last tag it rebuilds that tag's version. Exports `CADENCE_VERSION_NAME` and
   `CADENCE_VERSION_CODE`.
3. **One Gradle invocation** — the tests (unless `--skip-tests`: `:core:jvmTest :ui:jvmTest
   :app-desktop:test :ui:compileKotlinJvm`, plus `:core:testDebugUnitTest :ui:testDebugUnitTest`
   when an APK is wanted) and every packaging task, with `--console=plain --stacktrace`.
4. **Stage** into `dist/` under the names above.
5. **Signing check** when APKs were built — [`check-signing.sh`](../../.github/scripts/check-signing.sh)
   asserts the debug APK carries the certificate of `app-android/debug.keystore` and, when
   `CADENCE_KEYSTORE` is set, that the release APK does **not**.
6. Prints the `gh release create v<version> dist/* --title "Primico <version>" --generate-notes`
   command that would publish the result.

The sync endpoints (`CADENCE_NEON_DATA_API_URL`, `CADENCE_NEON_AUTH_URL`) are read from the same
environment — see [Configuration](configuration.md#build-time-environment).

## Version derivation

The version is derived from Conventional Commits, never edited.
[`next-version.sh`](../../.github/scripts/next-version.sh) reads the commits since the newest
`v*` tag:

| Commits since the last tag contain | Bump |
|---|---|
| a subject with `!` before the colon (`feat!:`, `fix(ui)!:`), or a `BREAKING CHANGE:` / `BREAKING-CHANGE:` footer | major |
| any `feat:` / `feat(scope):` | minor |
| anything else | patch |
| no `v*` tag exists | `1.0.0` |
| no commits since the tag | `skip=true` |

`version_code = major × 10000 + minor × 100 + patch`. Outputs (to `$GITHUB_OUTPUT`, else stdout):
`skip`, `version`, `tag` (`v<version>`), `version_code`, `bump`, `previous_tag`, `notes`. The notes
group subjects under *Features* (`feat`), *Fixes* (`fix`, `perf`) and *Other* (`build`, `chore`,
`ci`, `docs`, `refactor`, `revert`, `style`, `test`), with the prefix dropped and the short hash
appended.

Fallbacks when no version is passed in: Android `versionName` `0.0.0-dev` and `versionCode` `1`;
desktop `packageVersion` `1.0.0` (a `-suffix` is always cut off, since jpackage wants
`major.minor.patch`).

## Workflows

| Workflow | Trigger | Runner | Does |
|---|---|---|---|
| [`ci.yml`](../../.github/workflows/ci.yml) | every pull request; push to `main` | `blacksmith-4vcpu-ubuntu-2404` | The test command above; uploads the three modules' test reports on failure. No APK. 20 min timeout |
| [`pages.yml`](../../.github/workflows/pages.yml) | push to `main` touching `site/**`, `docs/**`, `docs-site/**` or the workflow; pull request touching `docs/**`, `docs-site/**` or the workflow; manual | same | Builds `docs-site/` (Node 22, `npm ci && npm run build`, which checks every link), copies `site/` to the root and the docs to `/docs`, deploys to GitHub Pages. Pull requests build without deploying |
| [`android.yml`](../../.github/workflows/android.yml) | `workflow_dispatch` only | same | Tests, then `build.sh apk --skip-tests`; uploads artifacts `primico-debug-apk` and `primico-release-apk` |
| [`desktop.yml`](../../.github/workflows/desktop.yml) | `workflow_dispatch` only | tests on Linux; packaging on the chosen OS | Tests (JVM only), then `build.sh desktop --skip-tests` per OS; uploads `primico-desktop-<runner OS>` |
| [`release.yml`](../../.github/workflows/release.yml) | `workflow_dispatch` only, **from `main` only** | Linux, plus macOS/Windows under `all` | Derives the version once, builds, publishes a GitHub Release |

`android.yml`, `desktop.yml` and `release.yml` never run on their own — a macOS runner is billed
at 10× and Windows at 2× a Linux minute. Every job has a `timeout-minutes`. Caching is
`gradle/actions/setup-gradle@v4` alone. [`dependabot.yml`](../../.github/dependabot.yml) opens
grouped update PRs, which `ci.yml` validates.

### Inputs

| Workflow | Input | Type | Default | Values |
|---|---|---|---|---|
| `desktop.yml` | `os` | choice | `ubuntu-latest` | `ubuntu-latest`, `macos-latest`, `windows-latest`, `all` |
| `release.yml` | `targets` | choice | `android+deb` | `android+deb` (APKs + `.deb`), `android` (APKs), `deb` (`.deb`), `all` (+ rpm, tarball, `.dmg`, `.msi`) |
| `release.yml` | `dry-run` | boolean | `false` | build and test, publish nothing |

### release.yml jobs

| Job | Runs when | Does |
|---|---|---|
| `preflight` | always | Refuses any ref but `refs/heads/main`; runs `next-version.sh`; refuses when `skip == 'true'` (nothing to release); writes the plan to the step summary |
| `build-android` | `targets != 'deb'` | Decodes the keystore secret, runs `build.sh apk` **with** tests; uploads `release-assets-android` |
| `build-desktop` | `targets != 'android'` | Linux only unless `all`; installs `fakeroot rpm` (Linux) or WiX (Windows); runs `build.sh deb` (named outright, so a missing tool fails) or `build.sh desktop` under `all`; tests only on the Linux leg; uploads `release-assets-<os>` |
| `publish` | not cancelled, nothing failed, not `dry-run` | Downloads every `release-assets-*`, writes the release body with a download list built from the files that actually landed, publishes tag `v<version>` named `Primico <version>` with `make_latest: true` |

The release body links each asset under *Android*, *Linux*, *macOS* and *Windows*, and — when APKs
were built — the permanent links
`https://github.com/<repo>/releases/latest/download/primico-release.apk` and
`…/primico-debug.apk`.

## Artefacts

| File | Built by | Notes |
|---|---|---|
| `primico-release.apk` | `build.sh apk` | application id `de.andi1984.cadence`; the one users should install |
| `primico-debug.apk` | `build.sh apk` | application id `de.andi1984.cadence.debug`, installs beside the release; always debug-signed |
| `*.deb`, `*.rpm` | `build.sh deb` / `rpm` | jpackage's own names; Linux package `primico`, menu group *Office* |
| `primico-linux-x64.tar.gz` | `build.sh tar` | the app image, packed by the script |
| `*.dmg` | `build.sh dmg` | bundle id `de.andi1984.cadence` |
| `*.msi` | `build.sh msi` | upgrade UUID `8f2b9c3e-6a3f-4b8a-9b7a-1e6f2c9d4a01`, menu group *Primico* |

The packaged desktop runtime explicitly includes the `java.sql` module, which jlink's scan misses
because the SQLite JDBC driver registers itself by reflection.

## Signing

| Key | Where it lives | Signs |
|---|---|---|
| Debug key | [`app-android/debug.keystore`](../../app-android/debug.keystore), **committed** (alias `androiddebugkey`, passwords `android`) | every debug APK, and the release APK when no release key is configured |
| Release key | outside any checkout (`~/.cadence-release/` by default); reaches CI as the `CADENCE_KEYSTORE_*` secrets | the release APK |

Android installs an update only when the certificate matches the installed app's, so the debug
key is committed to keep it identical on every machine and runner — AGP would otherwise invent a
fresh one per CI run. `tools/release-signing-wizard.sh` mints the release key (never overwriting
an existing one) and sets the six repository secrets listed in
[Configuration](configuration.md#ci-secrets).

## Related

- [Cut a release](../how-to/cut-a-release.md)
- [Run the tests](../how-to/run-tests.md)
- [Configuration](configuration.md)
