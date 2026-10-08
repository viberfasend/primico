---
title: "Plan: launcher actions on the desktop"
description: '"New task" and the main views offered from the dock, the app menu and the jump list, and the single-instance guard they all need first.'
---
*Written 2026-10-08. Nothing here is built yet; when work starts, the decisions move into an ADR
(next free number) and the steps into issues. Tracked in [#224](https://github.com/viberfasend/primico/issues/224).*

The in-app half of "shortcuts" — the shortcut table, the `?` sheet, and the hints that show the
keys where the controls are — is done; see [Keyboard and mouse](../reference/keyboard-shortcuts.md).
This plan is the other half: reaching Primico's verbs **from outside the window**, the way
Android's long-press launcher shortcuts do. Right-click the icon in the GNOME dash, the macOS Dock
or the Windows taskbar and get **New task**, **Today** and **Inbox**.

The tray menu already offers *Show*, *New task* and *Sync*
([`TrayMenu.kt`](../../app-desktop/src/main/kotlin/de/andi1984/cadence/desktop/ui/TrayMenu.kt)),
but only while the app runs and only where a tray exists. GNOME has had no tray without an
extension since 3.26.

## The prerequisite: one process

Every launcher action outside macOS works by **starting the app again with an argument**. Today
that starts a second process, with its own window, opening the same `cadence.db` and running its
own sync loop and reminder poll. That is already possible without launcher actions: launch
Primico twice from the menu, and two processes run (this was observed on the maintainer's machine
while writing this plan). So the guard is worth having on its own, whatever happens to the rest of
this plan.

**Shape:** at the top of `main()`, before `AppContainer()` opens the database, try an exclusive
`FileChannel.tryLock()` on `instance.lock` in `PlatformDirs.dataDir()`.

- **Lock taken:** this is the only instance. Bind a JDK 16+ Unix-domain socket
  (`UnixDomainSocketAddress`, which also works on Windows 10 1803 and later) at `instance.sock` beside
  it, and treat every line that arrives as an action.
- **Lock held by someone else:** connect, write this launch's action (or `show` when there is
  none), and exit 0 without ever opening the database.

The lock is the truth and the socket is only the doorbell, so a stale socket file left by a
crash is harmless. The new owner deletes it and binds again. The action vocabulary is a small
`LaunchAction` enum, parsed from `--new-task`, `--today`, `--inbox`, `--upcoming`, in a pure function
that can be tested like `shortcutFor`. Arriving actions feed the same `quickAddRequested` and
`navigator.switchTo` paths the shortcut table already drives, and also bring the window to the
front.

## Per platform

| | Linux (GNOME, KDE) | macOS | Windows |
|---|---|---|---|
| Surface | `.desktop` file `Actions=` → dash and dock right-click | Dock icon right-click | Taskbar or Start jump list *Tasks* |
| Static (works with the app closed) | yes | no: the Dock menu exists only while the app runs | yes |
| How the action arrives | argv of a new process → socket | in-process callback | argv of a new process → socket |
| Packaging work | custom `.desktop`, see below | none for the menu | AppUserModelID, see below |
| Runtime work | none | `Taskbar.setMenu(PopupMenu)` | COM `ICustomDestinationList` via JNA |
| New dependency | none | none | `jna` + `jna-platform` |

### Linux: a `.desktop` file with actions

The freedesktop spec allows `Actions=new-task;today;inbox;` in `[Desktop Entry]`, plus one
`[Desktop Action new-task]` group per action, each with its own `Name=`, `Name[de]=` and
`Exec=/opt/primico/bin/Primico --new-task`. GNOME Shell and KDE show these on right-click. A
localised `Name[de]=` is the one place a user-visible string lives outside `strings.xml`: the
desktop environment reads the file, not the app.

Getting jpackage to install that file is the open question. The Compose 1.11 `linux { }` DSL has no
option for it: `menuGroup`, `shortcut`, `appCategory` and the rest only fill jpackage's own template.
jpackage takes a replacement template from `--resource-dir`, and Compose does pass
`--resource-dir`, but it points at a directory Compose generates itself (the task's protected
`jpackageResources`). Ways in, in the order to prototype them:

1. A `doFirst` on `packageDeb`/`packageRpm` that writes the template into that directory before
   jpackage runs. This is cheap if the directory path is stable, but brittle across Compose
   upgrades.
2. Post-process the `.deb`/`.rpm`: unpack, patch the `.desktop`, repack (`dpkg-deb`, `rpmrebuild`).
   This is robust, but it's a second packaging step to keep working in `build.sh`.
3. Have the app write `~/.local/share/applications/primico.desktop` itself on first start. This
   overrides the system file per user and needs no packaging change. The cost is a file the
   uninstaller doesn't know about, and an `Exec=` path the app has to guess. It's also the only
   option for the tarball.

The `.desktop` file also needs `StartupWMClass` to match the AWT window class, or GNOME treats
the running window as a different app from the launcher entry and shows the actions on neither.

### macOS: the Dock menu

`java.awt.Taskbar.getTaskbar().setMenu(PopupMenu)` is supported on macOS
(`Taskbar.Feature.MENU`) and nowhere else. The items call straight into the running app, with no
argv and no socket involved. Launching from Finder never starts a second process on macOS
(LaunchServices hands the existing one the event instead), so the single-instance guard matters
less here. Labels come from resources, resolved the same way as `TrayLabels`.

If the actions should also work **with the app closed** (from Spotlight, Shortcuts.app, or a
script), that means a URL scheme: `CFBundleURLTypes` via `macOS { infoPlist { extraKeysRawXml } }`,
which the Compose DSL does support, and `Desktop.setOpenURIHandler` for `primico://new-task`. That
is a separate decision with a wider surface (any web page can link to a URL scheme) and is not
part of this plan.

### Windows: the jump list

A jump list's *Tasks* are `IShellLink`s, so command lines, built with `ICustomDestinationList`
(`BeginList` → `AddUserTasks` → `CommitList`). Java has no API for it. That means COM through JNA:
a new dependency, and the only native code in the app.

The catch is identity. Windows attaches the jump list to an **AppUserModelID**, and the taskbar
button only shows it if the running process, the pinned shortcut and the list all agree on that
ID. Java doesn't set one: `SetCurrentProcessExplicitAppUserModelID` would have to be called via
JNA before the first window exists. jpackage's MSI doesn't stamp one on the Start-menu shortcut
either (OpenJDK
[JDK-8188247](https://bugs.openjdk.org/browse/JDK-8188247) is the open request). This half
therefore needs a Windows machine to prove on, and it's the one most likely to cost more than it
returns.

## Order

1. **Single-instance guard and `LaunchAction` parsing.** Worth doing on its own, and testable on
   the JVM.
2. **macOS Dock menu.** About thirty lines with no packaging change, but it needs a Mac to see.
3. **Linux `.desktop` actions.** Prototype option 1, and fall back to 2.
4. **Windows jump list.** Only once 1–3 have shipped, and only if a Windows user asks.

A system-wide hotkey, like "quick add from anywhere" (`Ctrl`+`Alt`+`N` with the window unfocused),
is a different feature: it needs a native global-key hook on every OS, and Wayland doesn't allow
one at all. It's out of scope here.

## Sources

- [Desktop Entry Specification: additional application actions](https://specifications.freedesktop.org/desktop-entry-spec/latest/extra-actions.html)
- [`java.awt.Taskbar`](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/java/awt/Taskbar.html)
- [`ICustomDestinationList`](https://learn.microsoft.com/en-us/windows/win32/api/shobjidl_core/nn-shobjidl_core-icustomdestinationlist)
- [JDK-8188247: set AppUserModelID for java on Windows](https://bugs.openjdk.org/browse/JDK-8188247)
- [JDK-8232029: jpackage `.desktop` template lookup in `--resource-dir`](https://bugs.openjdk.org/browse/JDK-8232029)
