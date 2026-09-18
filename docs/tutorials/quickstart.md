---
title: Build and run Primico in five minutes
description: Clone the repository, launch the desktop app from source, run the test suite CI runs, and install a debug APK on a phone.
sidebar:
  order: 1
---

By the end of this page you will have Primico's desktop app running from source, the full test
suite green on your machine, and (if you have an Android phone) a debug build installed on it.
Nothing here needs an account, a server or a signing key.

"Five minutes" is the time once Gradle is warm. The very first run downloads Gradle 8.14.5, the
Kotlin and Compose toolchains and every dependency, which takes longer on a slow connection. Every
run after that is quick.

## What you need

| For | You need |
|---|---|
| The desktop app and the tests | **JDK 17 or newer** on your `PATH`. Nothing else. |
| The Android APK | The JDK, plus the **Android SDK** with platform 36 (`compileSdk` in [`app-android/build.gradle.kts`](../../app-android/build.gradle.kts)) |
| Installing on a phone | `adb` (part of the SDK's platform tools) and a phone with USB debugging on |

Install the JDK the usual way for your OS:

```bash
# Ubuntu / Debian
sudo apt install openjdk-17-jdk

# macOS (Homebrew)
brew install openjdk@17

# Windows (PowerShell)
winget install EclipseAdoptium.Temurin.17.JDK
```

Check it before going on:

```bash
java -version
```

```
openjdk version "17.0.…"
```

Any version from 17 up works. Gradle compiles for Java 17 whatever JDK runs it.

## 1. Clone the repository

```bash
git clone https://github.com/viberfasend/primico.git
cd primico
```

:::note
The product is called **Primico**, but the code still says **Cadence**: the Kotlin package is
`de.andi1984.cadence`, the main class is `CadenceViewModel`, the environment variables start with
`CADENCE_`. That is on purpose, see [naming](../concepts/naming.md). Don't "fix" it.
:::

## 2. Run the desktop app from source

```bash
./gradlew :app-desktop:run
```

On Windows, use the batch wrapper instead:

```powershell
.\gradlew.bat :app-desktop:run
```

A window titled **Primico** opens on the empty state. There is no sample data: a fresh install
starts empty on purpose. Press `Ctrl`+`N` (`Cmd`+`N` on macOS) or use the add button, type
`Water the plants tomorrow !p2`, and watch the date and the priority turn into chips as you type.

Gradle keeps running (the progress bar sits at `> :app-desktop:run`) until you close the window.
That's expected, because the app is a child process of the build.

:::caution
A source build keeps its data in the **same directory an installed Primico uses**:
`~/.local/share/primico/` on Linux, `~/Library/Application Support/Primico/` on macOS,
`%APPDATA%\Primico\` on Windows. If you already use Primico on this machine, the build from source
opens your real task list. Export a backup first (Settings → Data → Export backup), or use a
separate OS user for development.
:::

## 3. Run the test suite CI runs

This is the one command CI runs on every pull request. Keep it as one invocation: configuring this
build costs more than running it, so a second `./gradlew` pays the configuration cost all over
again.

```bash
./gradlew :core:testDebugUnitTest :ui:testDebugUnitTest \
          :core:jvmTest :ui:jvmTest :app-desktop:test :ui:compileKotlinJvm
```

Passing tests print nothing (the build only logs failures), so a green run ends like this:

```
BUILD SUCCESSFUL in 41s
```

The tests themselves take about five seconds. If the command sits there for minutes, a test is
hanging rather than failing. [Run the tests](../how-to/run-tests.md) explains the usual cause and
the five-minute timeout that catches it.

:::note
The `:core:testDebugUnitTest` and `:ui:testDebugUnitTest` tasks compile against Android, so this
command needs the Android SDK too. With no SDK you can still run the desktop half on its own:
`./gradlew :core:jvmTest :ui:jvmTest :app-desktop:test :ui:compileKotlinJvm`.
:::

## 4. Build the debug APK and install it

Tell Gradle where the SDK is, either with `ANDROID_HOME` or with a `local.properties` file at the
repository root (it is git-ignored):

```properties
sdk.dir=/home/you/Android/Sdk
```

If the SDK is missing platform 36, accept the licences once (`sdkmanager --licenses`) and Gradle
downloads it on the first build. Then:

```bash
./gradlew assembleDebug
```

The APK lands at `app-android/build/outputs/apk/debug/app-android-debug.apk`. With the phone
plugged in and USB debugging allowed:

```bash
adb devices
adb install -r app-android/build/outputs/apk/debug/app-android-debug.apk
```

```
Performing Streamed Install
Success
```

The debug build's application id is `de.andi1984.cadence.debug`, so it installs **next to** a
released Primico rather than over it, and the two keep separate data. To start it from the
terminal:

```bash
adb shell am start -n de.andi1984.cadence.debug/de.andi1984.cadence.MainActivity
```

:::tip
Every debug build, including the one CI publishes, is signed with the committed key
`app-android/debug.keystore`, so a debug APK you build installs over any other debug APK
without an uninstall.
:::

## 5. What a source build does not have: sync

A build made without the two sync endpoints has **no sync at all**. Settings shows a paragraph
instead of the sign-in form, the header shows no sync indicator, and the app never makes a
network request. Everything else works exactly the same, because the database on the device is
the source of truth.

The endpoints are compiled in from `CADENCE_NEON_DATA_API_URL` and `CADENCE_NEON_AUTH_URL` at
build time, and there is no default. To sync your own devices you point a build at a Neon project
you own. [Self-hosting sync](../self-hosting.md) walks through it.

## Where to go next

- [A tour of the code](tour-of-the-code.md) follows one typed task from the quick-add sheet into
  SQLite and out to the server.
- [Your first contribution](first-contribution.md) adds a small feature with a test and opens a
  pull request.
- [Architecture](../concepts/architecture.md) explains the four modules and why they're split
  that way.
- [Build and CI reference](../reference/build-and-ci.md) lists every Gradle task and workflow.
