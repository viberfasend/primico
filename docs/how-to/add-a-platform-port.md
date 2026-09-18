---
title: Add a platform port
description: When Android and the desktop must do something differently, declare it as a port in :ui, implement it in each shell, wire it through ViewModelAdapters, and fake it for tests — or decide it belongs in CadenceCore instead.
sidebar:
  order: 5
---

The screens in `:ui` never learn which platform they run on. Everything the two shells do
differently (alarms, file pickers, opening a file with another app, where settings are stored)
reaches `:ui` through a small interface called a **port**, and each shell implements it. This
guide adds a new one.

## Prerequisites

- You know the module layout: [architecture](../concepts/architecture.md) and the
  [modules reference](../reference/modules.md).
- An Android SDK, because you'll compile both shells.

## First, decide whether it's a port at all

| The thing… | Put it… | Examples |
|---|---|---|
| differs **per machine**: OS APIs, file dialogs, notifications, system integration | a **port** in [`ui/platform/Ports.kt`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/platform/Ports.kt), implemented per shell | `ReminderScheduler`, `BackupGateway`, `BackupFilePicker`, `AttachmentFilePicker`, `AttachmentOpener` |
| is **identical on both** (plain JVM code: HTTP, JSON, SQL, hashing) | a concrete class in `:core`, built once in [`CadenceCore`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/data/CadenceCore.kt) | `CadenceRepository`, `CadenceSyncEngine`, `BlobStore` |
| is a per-device **setting** | the existing [`SettingsStore`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/settings/SettingsStore.kt) port and `CadenceSettings` | theme, density, reminders on/off |
| is desktop **window** state with no meaning on a phone | `:app-desktop` only (`DesktopWorkspaceStore`, `workspace.json`) | sidebar width, window bounds |

**Sync is deliberately not a port.** HTTPS and JSON are the same on both platforms, so
`CadenceSyncEngine` is one concrete class in `:core` that `CadenceCore` constructs once on the
application scope ([ADR 0002](../adr/0002-supabase-sync.md), decision 7). Making it a port would
buy two implementations of the same code and a fake that asserts nothing. The same test applies
to yours: if both implementations would be the same code, it isn't a port.

## Steps

The running example is a hypothetical `ClipboardWriter` that copies a task's title.

### 1. Declare the interface in `Ports.kt`

```kotlin
/** Puts text on the system clipboard. Answers whether the platform took it. */
interface ClipboardWriter {
    fun copy(text: String): Boolean
}
```

Guidelines the existing ports follow:

- **Speak domain types and plain values**, never `Context`, `Uri` or `java.awt` types. An opaque
  handle only `:ui` hands back is fine: `BackupTarget` is a SAF uri string on Android and a path on
  the desktop.
- **Answer instead of throwing** when "nothing happened" is an ordinary outcome. `AttachmentOpener`
  returns `false` on a phone with no PDF viewer, and the ViewModel shows a snackbar.
- **Use a callback when the answer arrives later.** `BackupFilePicker` and `AttachmentFilePicker`
  call back rather than return, because Android's answer is an Activity result that arrives
  after the composition that asked.

### 2. Implement it in both shells

- **Android** (`app-android/src/main/java/de/andi1984/cadence/…`): for example, an
  `AndroidClipboardWriter(context)` over `ClipboardManager`.
- **Desktop** (`app-desktop/src/main/kotlin/de/andi1984/cadence/desktop/…`): for example, a
  `DesktopClipboardWriter` over `java.awt.Toolkit`. Guard for a headless JVM, the way
  `DesktopAttachmentOpener` guards `java.awt.Desktop`.

### 3. Wire it: container singleton or composition-bound

There are two ways a port reaches the code that uses it. Pick the one that matches its lifetime.

**A container singleton the ViewModel calls** (like `ReminderScheduler`, `BackupGateway`,
`AttachmentOpener`):

1. Add it to [`ViewModelAdapters`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/CadenceViewModelFactory.kt)
   and pass it through `cadenceViewModel(core, adapters, scope)` into a new
   [`CadenceViewModel`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/CadenceViewModel.kt)
   constructor parameter.
2. Construct it in each shell's `AppContainer` and add it to the `ViewModelAdapters(...)` built
   there:
   [`app-android/…/AppContainer.kt`](../../app-android/src/main/java/de/andi1984/cadence/AppContainer.kt)
   and [`app-desktop/…/AppContainer.kt`](../../app-desktop/src/main/kotlin/de/andi1984/cadence/desktop/AppContainer.kt).
3. Add a ViewModel method that calls it, and give the screen a callback.

**A composition-bound helper a screen calls** (like the two file pickers):

1. On Android, expose it as a `@Composable fun rememberSaf…(): YourPort`, as in
   [`SafBackupFilePicker.kt`](../../app-android/src/main/java/de/andi1984/cadence/ui/backup/SafBackupFilePicker.kt),
   because an Activity-result launcher can only be created inside a composition. On the desktop,
   `remember { Desktop…() }` in
   [`CadenceDesktopApp.kt`](../../app-desktop/src/main/kotlin/de/andi1984/cadence/desktop/ui/CadenceDesktopApp.kt).
2. Pass it into the screen composable as a parameter, from
   [`CadenceApp.kt`](../../app-android/src/main/java/de/andi1984/cadence/ui/CadenceApp.kt) on
   Android and `CadenceDesktopApp.kt` on the desktop.

Wiring is hand-rolled; there's no DI framework. A singleton shared by both shells goes in
`CadenceCore`, and a platform-specific one goes in the shell's `AppContainer`.

### 4. Fake it for the tests

Add a hand-written fake to `:ui`'s
[`Fakes.kt`](../../ui/src/jvmSharedTest/kotlin/de/andi1984/cadence/ui/Fakes.kt). There's no
mocking library in this repository. The house style is a **recording** fake that keeps what it
was asked and can be told to refuse:

```kotlin
class RecordingClipboardWriter : ClipboardWriter {
    val copied = mutableListOf<String>()
    var accepts: Boolean = true

    override fun copy(text: String): Boolean {
        copied += text
        return accepts
    }
}
```

If you added a constructor parameter to `CadenceViewModel`, update every test that constructs it
directly (`CadenceViewModelCrudTest` and `CadenceViewModelUndoTest` in `ui/src/jvmTest`). Only
**platform** ports are ever faked. The stores and the repository under the ViewModel stay real
(see [testing philosophy](../concepts/testing-philosophy.md)).

### 5. Test the platform half where you can

A desktop implementation with no Compose in it can have a plain JVM test in
`app-desktop/src/test`, like `DesktopSettingsStoreTest` and `DesktopReminderSchedulerTest`.
Inject whatever a headless runner lacks: `DesktopReminderScheduler` takes a `notify` lambda so the
tests never need a real system tray.

## Verify

The CI command never compiles `:app-android`, so run the Android build as well:

```bash
./gradlew :core:testDebugUnitTest :ui:testDebugUnitTest \
          :core:jvmTest :ui:jvmTest :app-desktop:test :ui:compileKotlinJvm \
          assembleDebug
```

Then exercise the feature in both shells: `./gradlew :app-desktop:run`, and the debug APK on a
phone ([quickstart](../tutorials/quickstart.md)).

## Related

- [Desktop shell](../concepts/desktop-shell.md): what the desktop does differently, and the two
  composition locals Android leaves at their defaults.
- [Architecture](../concepts/architecture.md): `CadenceCore`, the two `AppContainer`s and the one
  ViewModel.
- [ADR 0001](../adr/0001-desktop-app-and-multi-device-sync.md): how the shared modules were carved
  out of the Android app.
