---
title: Keyboard and mouse
description: Every desktop keyboard shortcut, the right-click menus, and every drag-and-drop target, with the file each is defined in.
sidebar:
  order: 7
---

Everything on this page is the **desktop** app. Android has none of it: the row menus and the
drag gesture ride on composition locals that `:app-android` leaves at their disabled defaults.
The design is [ADR 0003](../adr/0003-desktop-interaction-model.md); the shell around it is
[Desktop shell](../concepts/desktop-shell.md).

## Keyboard shortcuts

Every window shortcut is one row in `CADENCE_SHORTCUTS` in
[`Shortcuts.kt`](../../app-desktop/src/main/kotlin/de/andi1984/cadence/desktop/ui/Shortcuts.kt).
The same list drives both the dispatcher (`onKeyEvent` in
[`Main.kt`](../../app-desktop/src/main/kotlin/de/andi1984/cadence/desktop/Main.kt)) and the
cheat sheet ([`ShortcutSheet.kt`](../../app-desktop/src/main/kotlin/de/andi1984/cadence/desktop/ui/ShortcutSheet.kt)),
so the two cannot disagree. **Primary** is `Ctrl` on Linux and Windows and `Cmd` on macOS. A
shortcut matches only with exactly its modifiers held, on key-down.

### Global

| Keys | Action |
|---|---|
| Primary + `N` | New task (opens quick-add) |
| Primary + `K` | Command palette |
| Primary + `F` | Search |
| Primary + `,` | Settings |
| Primary + `B` | Show or hide the sidebar (collapses it to an icon rail) |
| Primary + `R` | Sync now — does nothing when signed out |
| Primary + `Z` | Undo the pending delete |
| `Shift` + `/` (`?`) | Keyboard shortcuts cheat sheet |

### Navigation

| Keys | Action |
|---|---|
| `Alt` + `←` (macOS `⌥` + `←`) | Back |
| `Alt` + `→` (macOS `⌥` + `→`) | Forward |
| `Esc` | Back (closes the current detail) |
| Primary + `1` | Today |
| Primary + `2` | Upcoming |
| Primary + `3` | Inbox |
| Primary + `4` | Projects |

Primary + `1`…`4` switch views (`DesktopNavigator.switchTo`); Search and Settings push onto the
back stack (`go`).

### Selected row

Bare keys that act on the row the keyboard is on (`RowSelectionState`). They reach the window only
when no focused field has taken them first, so typing in the quick-add field never completes a
task. A key with no row selected is not consumed.

| Keys | Action |
|---|---|
| `J` or `↓` | Select the next row |
| `K` or `↑` | Select the previous row |
| `Enter` | Open the selected task |
| `X` | Mark done / not done |
| `Delete` | Delete (with the usual undo) |
| `1` / `2` / `3` / `4` | Priority P1 / P2 / P3 / P4 |
| `T` | Due today |
| `M` | Due tomorrow |
| `W` | Due in one week (today + 7) |
| `0` | Remove the due date |

### Inside the command palette

Handled by the palette's text field
([`CommandPaletteDialog.kt`](../../app-desktop/src/main/kotlin/de/andi1984/cadence/desktop/ui/CommandPaletteDialog.kt)),
not by the window table. Prefixes and scoring are in [Quick-add grammar](quick-add.md#command-palette-prefixes).

| Keys | Action |
|---|---|
| `↓` / `↑` | Move the highlight (wraps around) |
| `Enter` or numpad `Enter` | Run the highlighted entry |
| `Esc` | Close |

## Right-click menus

Opened by a secondary click (`Modifier.secondaryClickable` inside `CadenceContextMenu`,
[`ContextMenu.kt`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/components/ContextMenu.kt)).
Submenus are pages of the same menu with a back row, not cascades. Items are defined in
[`RowMenus.kt`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/components/RowMenus.kt).

### Task row

On every `TaskRow` in every list, when `RowInteractions.enabled`.

| Item | Submenu / effect |
|---|---|
| Open | opens the task |
| Mark done / Mark not done | toggles completion |
| Importance ▸ | Critical, High, Normal, Low (P1–P4) |
| Due date ▸ | Today, Tomorrow, Next week, No due date |
| Move to project ▸ | Inbox, then every project |
| Move to section ▸ | No section, then the project's sections — only shown when the task is in a project that has sections |
| Duplicate | copies the task |
| Copy title | puts the title on the clipboard |
| Delete | deletes, with undo |

### Project row (sidebar)

| Item | Submenu / effect |
|---|---|
| Open | opens the project |
| Add task here | quick-add filed under this project |
| New subproject | only on a top-level project |
| Edit project | the project dialog |
| Nest under ▸ | Top level (enabled for a subproject), then every project it may nest under — only shown when the project has no subprojects of its own |
| Delete project | the delete dialog |

### Menus behind a button

Section headings and tag rows have an overflow button rather than a right-click menu:

| Where | Items |
|---|---|
| Section heading (project detail) | Rename section, Delete section |
| Tag row (Tags screen, both shells) | Edit tag, Delete tag, Move up (not on the first row), Move down (not on the last row) |

## Drag and drop

Desktop only: `DragAndDropHost` wraps the desktop window and nothing on Android. What a drop
*means* is decided in one pure function, `resolveDrop`, in
[`DragModel.kt`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/dnd/DragModel.kt); the gesture,
ghost and highlight are in [`DragAndDrop.kt`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/dnd/DragAndDrop.kt).
A drop that would change nothing, or is not allowed, resolves to `DropIntent.Rejected` and the
target does not light up. When zones overlap, the smallest one under the pointer that accepts the
drop wins.

### What can be dragged

| Payload | Drag source |
|---|---|
| `TaskDrag` | any `TaskRow` |
| `ProjectDrag` | a project row in the desktop sidebar |
| `TagDrag` | a tag row on the Tags screen (the desktop passes `reorderable = true`) |

### Drop targets

| `DropTarget` | Registered on | A task dropped here | A project dropped here | A tag dropped here |
|---|---|---|---|---|
| `IntoProject(projectId)` | sidebar project rows | moves to that project, section cleared | nests under it (one level, no cycles, not onto a nested project, not if it has subprojects) | rejected |
| `IntoProject(null)` | sidebar Inbox row | moves to the Inbox | lifts a subproject back to the top level | rejected |
| `IntoSection(projectId, sectionId)` | section headings and the "No section" heading in a project | files under that band, moving projects if needed | rejected | rejected |
| `OntoDate(date)` | Upcoming day headings; the sidebar Today row (`today`) | reschedules to that date | rejected | rejected |
| `IntoTag(tagId)` | sidebar tag rows | **adds** the label, keeping the others; rejected if already worn | rejected | rejected |
| `Between(list, index, orderedIds)` | the gaps between rows (`DropCaret`) | reorders; into a different project/section list it moves and orders in one intent | reorders among siblings with the same parent only | reorders the tag list |

`Between` lists, by `OrderedList` variant:

| `OrderedList` | Where | Meaning |
|---|---|---|
| `Tasks(projectId, sectionId)` | Inbox (`null`), project bands | a container: dropping here files the task there |
| `LooseTasks` | Today | order only — the task keeps its project |
| `Projects(parentId)` | sidebar project tree (`null` = top level) | project order |
| `Sections(projectId)` | — (declared; no section is draggable yet) | section order |
| `Tags` | Tags screen | tag order |

The resulting `DropIntent`s are `MoveTask`, `RescheduleTask`, `TagTask`, `NestProject`,
`ReorderTasks` (optionally carrying a `MoveTask`), `ReorderProjects`, `ReorderSections`,
`ReorderTags` and `Rejected`. Only root rows get carets: a subtask's place is its checklist's.

## Related

- [Quick-add grammar](quick-add.md) — what to type once `Ctrl`/`Cmd`+`N` is open
- [Desktop shell](../concepts/desktop-shell.md)
- [ADR 0003](../adr/0003-desktop-interaction-model.md)
