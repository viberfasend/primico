---
title: The desktop shell
description: How the desktop app becomes pointer- and keyboard-first — sidebar, two panes, drag and drop, command palette — while almost all of it stays shared code Android switches off.
sidebar:
  order: 9
---

The desktop app is not a phone app in a window. It has a resizable sidebar with the whole project
tree, a two-pane layout on wide windows, back *and* forward navigation, right-click menus, drag
and drop, keyboard selection, and a command palette. The surprising part is where that code lives:
**almost all of it is shared code in `:ui`** that Android simply leaves switched off. The shell in
`:app-desktop` adds only the window, the navigator and the files.

The decisions are recorded in [ADR 0003](../adr/0003-desktop-interaction-model.md); this page
explains how they fit together.

## What lives where

| Piece | Where | Android |
|---|---|---|
| Sidebar, navigator, shortcuts table, command palette dialog, tray | `:app-desktop` | — |
| Row right-click menus, drag gesture, keyboard selection | `:ui` (`components/RowInteractions.kt`, `components/RowSelection.kt`, `components/RowMenus.kt`) | compiled in, switched off |
| Drag rules: payloads, drop targets, `resolveDrop`, `hitTest` | `:ui` (`dnd/DragModel.kt`) | compiled in, unused |
| Command palette ranking | `:ui` (`palette/CommandPaletteModel.kt`) | compiled in, unused |
| Every screen | `:ui` | the same composables |

## The sidebar

A rail of four icons is a phone's bottom bar stood on its side.
[`Sidebar.kt`](../../app-desktop/src/main/kotlin/de/andi1984/cadence/desktop/ui/Sidebar.kt) shows
the views, the whole project tree with counts, and the tag list — resizable, foldable, and with a
**drop target on every row**. Dropping a task on a project files it there; dropping it on a tag
labels it. That is the part a rail could never be, and the reason the sidebar exists.

The Projects *screen* stays: creating, editing and deleting a project are dialogs that want a page
behind them rather than a 268 dp column.

## Two panes are one navigator at two widths

At **1000 dp** and wider, the window draws two panes, and a detail route — a task, a project, a
tag — fills the second pane instead of replacing the list. Below that it pushes as a destination,
the way Android does.

[`DesktopNavigator`](../../app-desktop/src/main/kotlin/de/andi1984/cadence/desktop/ui/Navigation.kt)
holds three things: a back stack, a **forward** stack (`Alt`+`→`, which `NavHost` never offered),
and the detail pane's occupant. `go()` decides pane-or-push from the current width; `back()`
undoes whichever happened. Resizing across the threshold calls `onWidthChanged`, which *moves* an
open detail between the pane and the stack rather than dropping it — so `Esc` and `Alt`+`←` keep
meaning one thing while a window is being dragged wider
([ADR 0003, decision 7](../adr/0003-desktop-interaction-model.md#7-two-panes-are-one-navigator-at-two-widths)).

The screens know none of this: `TaskDetailScreen` is the same composable in a pane and in a
window. `DesktopNavigatorTest` pins the stacks and the resize behaviour.

**Why not `androidx.navigation`?** It is an Android-only artifact, and it has no forward stack and
no notion of a second pane. Navigation is the one part of the UI that stays per shell anyway.

## Row behaviour through composition locals

Seven screens draw task rows. Giving each of them right-click menus and a drag handle through
parameters would have changed seven signatures for a feature one shell uses. Instead, two
composition locals carry it:

- **`LocalRowInteractions`** — a `RowInteractions` value: the menu callbacks, the state the menus
  need to build their submenus, and `enabled`.
- **`LocalRowSelection`** — a `RowSelectionState`: which row the keyboard is on.

`:app-desktop` provides both around its content; `TaskRow` reads them. `:app-android` provides
nothing, `enabled` stays `false`, and its rows are exactly the rows they were.

Keyboard selection is read off the screen, not off a list: rows **register where they are**, and
`RowSelectionState` orders them by vertical position. Nothing in `:ui` has "the list" — screens
draw rows in bands, under headings, sometimes the same task twice — so the order the user sees is
the only one that stays right. A row that scrolls away or is deleted takes the selection with it,
so `x` never completes something nobody can see.

:::caution[`assembleDebug` compiling is not proof Android is unaffected]
The shared screens now read two locals that Android leaves at their defaults. A change to
`RowInteractions` or `RowSelectionState` changes what Android runs even when nothing in
`:app-android` was touched — check the defaults, not just the build.
:::

## Drag and drop is ours

Compose Multiplatform has `Modifier.dragAndDropSource`/`Target`, and it is the wrong tool here:
its payload wraps an AWT `Transferable` on one platform and a `ClipData` on the other, every drag
in this app is in-process and carries a domain object, it has no insertion caret between rows,
and none of it is reachable from a unit test.

So `:ui/dnd/` is two files. [`DragModel.kt`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/dnd/DragModel.kt)
is pure: `DragPayload`, `DropTarget`, `DropIntent`, `resolveDrop` and `hitTest`.
`DragAndDrop.kt` is the gesture, the ghost, the caret and the highlight, and decides nothing.
That split is what makes "a project cannot nest under its own child" a test in `DragModelTest`
rather than a thing to remember.

The drop targets are `IntoProject`, `IntoSection`, `OntoDate`, `IntoTag` and `Between` (a
position in an ordered list). Two rules worth knowing:

- **A drop that would change nothing resolves to `Rejected`**, not a no-op write. The hover
  highlight is driven by the same function, so a row that lights up is a promise.
- **Any new drop must be a `DropTarget` resolved in `resolveDrop`.** A screen that handles a drop
  itself is a rule no test covers.

`DragAndDropHost` wraps the desktop window and nothing on Android, where a drag source would
swallow the list's scroll and then do nothing.

## One shortcut table, two readers

[`Shortcuts.kt`](../../app-desktop/src/main/kotlin/de/andi1984/cadence/desktop/ui/Shortcuts.kt) is
data. The window dispatches key events from it, and the `?` cheat sheet is generated from it, so a
shortcut cannot be wired but undocumented, or documented but dead. `Ctrl` versus `Cmd` is decided
once, by reading the OS. The full list is in [Keyboard shortcuts](../reference/keyboard-shortcuts.md).

The command palette (`Ctrl`/`Cmd`+`K`) fuzzy-matches commands, projects, tags and tasks. A
leading `>` narrows it to commands, `#` to projects and `@` to tags — the same characters quick
add uses, so the palette teaches no second syntax. Its ranking lives in `:ui`'s `CommandPaletteModel`, tested
on the JVM; the dialog is the desktop's.

## Window state is a second file

Sidebar width, folded projects, window size, position and maximised state live in
`workspace.json` ([`DesktopWorkspaceStore`](../../app-desktop/src/main/kotlin/de/andi1984/cadence/desktop/data/DesktopWorkspaceStore.kt)),
next to `settings.json` — **not** in `CadenceSettings`. None of it means anything on a phone, and
putting it in the shared settings object would give Android's `SharedPrefsSettingsStore` fields
it can never set. Every value is clamped on read: a window position from a monitor that is no
longer plugged in is a window nobody can find.

## Settings writes leave the UI thread

`SettingsStore`'s setters are not `suspend`. On Android that is fine — `SharedPreferences` writes
asynchronously itself. On the desktop it put a synchronous file write on the Compose UI thread
every time someone flipped a switch ([#104](https://github.com/viberfasend/primico/issues/104)). So
[`DesktopSettingsStore`](../../app-desktop/src/main/kotlin/de/andi1984/cadence/desktop/data/DesktopSettingsStore.kt)
updates its flow on the spot and writes on the application scope, through a temp file and a
rename — the old in-place write, interrupted by a crash, left a truncated `settings.json` that
loaded as "no settings at all".

- **A write puts down the state it finds, never the value that started it.** Coroutines launched
  in order do not reach a lock in order, so an older write would otherwise rename a stale file
  over a newer one.
- **`load()` stays synchronous.** It runs once before any window exists; loading asynchronously
  would paint the defaults and then swap them.

### One exit path

The writes run on daemon threads and would die with the process — and the toggle someone flipped
a second before quitting is exactly the one to keep. Both ways out, the window's
`onCloseRequest` and the tray menu's Quit, call `AppContainer.shutdown()`, which does three things
in order:

1. `syncEngine.syncInBackground()` — a last fire-and-forget round,
2. `settingsStore.flush()` — block until pending settings writes have landed,
3. `core.close()` — cancel the application scope and close the database driver.

`viewModel.close()` stays outside it, in `main()`, because the ViewModel's scope belongs to
`main()`, not the container. Any test that reads the settings file back must call `flush()` too.

## Related

- [ADR 0003 — The desktop interaction model](../adr/0003-desktop-interaction-model.md)
- [Keyboard shortcuts](../reference/keyboard-shortcuts.md)
- [Architecture at a glance](architecture.md)
- [Tasks, projects and tags](tasks-projects-tags.md#manual-order-dense-integers-from-0)
- [Testing philosophy](testing-philosophy.md)
