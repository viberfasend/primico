---
title: Glossary
description: The words this codebase uses in a specific sense — from adapter to watermark — each with a short definition and where it is explained.
sidebar:
  order: 10
---

Terms as the code and these docs use them. Class and file names are the real ones.

### Adapter

The implementation of a [port](#port) on one platform: `SqlDelightTaskStore` for
`TaskStore`, `AlarmReminderScheduler` and `DesktopReminderScheduler` for `ReminderScheduler`.
See [Architecture](../concepts/architecture.md).

### After-completion rule

A recurrence with `RecurrenceMode.AFTER_COMPLETION`: the next date
counts from the day the task was finished, not from its due date (`3 days after done`). See
[Recurrence](../concepts/recurrence.md).

### AppContainer

The hand-rolled dependency graph of one shell: `:app-android`'s is built in
`CadenceApplication.onCreate`, `:app-desktop`'s at the top of `main()`. It holds only what is
platform-specific and wraps a [`CadenceCore`](#cadencecore). See [Modules](modules.md).

### Attachment

A link or a file filed on a task. The row is local to the device and is never
synced or backed up; a file's bytes are a [blob](#blob). See [Attachments](../concepts/attachments.md)
and [Data model](data-model.md#attachment).

### Band

One group of rows in a list: a [section](#section) of a project, the project's
ungrouped band (`sectionId = null`), the overdue block in Today, or a day in Upcoming.

### Blob

The bytes of a file attachment, stored once under `attachments/<2 hex>/<sha256>` by
`BlobStore` and shared by every row naming the same hash. A missing blob is a state the UI draws,
not an error. See [content address](#content-address).

### CadenceCore

`:core`'s shared dependency graph: the database, the blob store, the
`CadenceRepository`, the `CadenceSyncEngine` and the application scope they run on. Each shell
opens the database driver and hands it over.

### CadenceUiState

The one immutable state object the one `CadenceViewModel` exposes. Every
screen receives all of it; derived lists (overdue, inbox, a project's tasks) are methods on it.

### Command palette

The desktop's `Ctrl`/`Cmd`+`K` dialog: a fuzzy search over commands,
projects, tags and open tasks, narrowed by a `>`, `#` or `@` prefix. See
[Quick-add grammar](quick-add.md#command-palette-prefixes).

### Content address

Naming bytes by their own hash. An attachment blob's only name is the
lowercase hex SHA-256 of its content, which is what de-duplicates two rows pointing at one file.

### Cursor

Per synced table, the newest `server_updated_at` this device has pulled, stored as
ISO-8601 text in `syncStateRow`. The next pull asks for rows at or after it, minus a five-second
overlap. It is the **server's** clock. Compare [watermark](#watermark). See
[Sync wire format](sync-wire.md#a-round).

### Danger zone

Settings → *Delete all data*, the only wipe in the app. It is still a
[tombstone](#tombstone) of every row, behind a dialog and the normal [undo window](#undo-window).
See [Undo and deletes](../concepts/undo-and-deletes.md).

### Data API

Neon's PostgREST endpoint over the project's Postgres; the sync engine pulls,
upserts and sweeps rows through it with a Bearer JWT. See
[Sync wire format](sync-wire.md#requests).

### Detail pane

On the desktop at 1000 dp and wider, the right-hand pane a task, project or tag
detail opens in instead of replacing the list. See [two-pane](#two-pane).

### Drop target

A place something can be dropped (`DropTarget`: `IntoProject`, `IntoSection`,
`OntoDate`, `IntoTag`, `Between`). `resolveDrop` turns a payload plus a target into a
`DropIntent`. See [Keyboard and mouse](keyboard-shortcuts.md#drag-and-drop).

### Foreground poll

`CadenceSyncEngine.startForegroundPoll()`: a full sync round every 60
seconds. Android runs it between `onStart` and `onStop`; the desktop for the whole process. It
stands where realtime delivery stood before the move to Neon.

### Grace

How long after an alarm's trigger time `ReminderReconciler` leaves it alone once
nothing plans it any more, because it may still be in flight: ten minutes on Android, zero on the
desktop. See [Reminders](../concepts/reminders.md).

### Handle

A tag's name with its spaces removed (`Tag.handle`): what `@…` matches in quick-add,
so `@deepwork` reaches *Deep Work*.

### Inbox

Where a task with no project lives (`projectId = null`). Not a row in any table.

### jvmShared

The hand-declared source set in `:core` and `:ui` that holds all hand-written
code. Both targets (Android and the desktop JVM) depend on it, so it may use the JDK;
`commonMain` holds only generated code. See [Modules](modules.md#source-sets).

### Last-writer-wins

The one conflict rule, on the device and on the server: the record with the
greater `updatedAt` wins whole; ties keep what is stored; a tombstone competes like any version.

### Lead minutes

`CadenceSettings.reminderLeadMinutes`: per device, "notify me this many minutes
before a task's `dueTime`", one notification per entry. A task's own `reminderTime` fires as
lead `0`.

### Local write

A change made on this device through `CadenceRepository`'s `write { }` wrapper.
Each one ticks `CadenceRepository.localWrites`, which arms the two-second sync debounce. Rows
merged in by a pull are not local writes.

### Neon Auth

Neon's hosted Better Auth. The app signs in with email and password, keeps the
session cookie, and trades it for short-lived JWTs at `GET /token`. See
[Sync wire format](sync-wire.md#sign-in-and-the-access-token).

### Occurrence

One row in a recurring task's chain: the instance due on one date. Completing
it keeps the row and inserts the [successor](#successor).

### Pending delete

A delete the user has not been able to undo yet: its rows are hidden at once
(`CadenceUiState.pendingDeleteIds`) and nothing is written until the [undo window](#undo-window)
passes.

### Port

An interface stating what the app needs from a platform or from storage, in its own
vocabulary: the store ports in `data/Stores.kt`, the platform ports in `ui/platform/Ports.kt`, and
`SettingsStore`. Sync is deliberately not a port. See [Architecture](../concepts/architecture.md).

### Priority

`P1` (Critical) to `P4` (Low), default `P3`. Every list sorts by it first and by
due date second, and it is never shown by colour alone. See [Data model](data-model.md#priority).

### Reconcile

What a reminder scheduler does on every task emission: `ReminderReconciler`
diffs what is armed against what `ReminderPlanner` plans and answers `Arm`, `Cancel` or `Keep`
per (task, lead) key.

### Revival

Importing's one exception to last-writer-wins: a live record in a backup file that
lands on a row this device has tombstoned brings it back, stamped with the import's clock. Sync
never revives. See [Backup file format](backup-format.md#merge-on-import).

### Root task

A task with no `parentId`. The container lists (Inbox, projects) show root tasks
and hide completed occurrences that have been replaced (`CadenceUiState.rootTasks`).

### Round

One `CadenceSyncEngine.syncOnce()`: pull, merge, push, and at most daily a
[sweep](#sweep), under a mutex, every step idempotent. See [Sync wire format](sync-wire.md#a-round).

### Section

A heading inside one project's list (`Section`, `sectionRow`). Tasks under it still
belong to the project; deleting it ungroups them. Not a subproject. See
[Data model](data-model.md#section).

### Server clock

`server_updated_at`, set only by the Postgres trigger. What cursors read, so a
device with a wrong clock can lose a conflict but never hide its rows.

### Session cookie

Better Auth's long-lived credential, captured from `Set-Cookie` at sign-in
and stored in `syncStateRow.session` with the current JWT and the email (`NeonSession`).

### spawnedFromId

On a task, the id of the finished [occurrence](#occurrence) whose completion
inserted it: the only link between two rows of a recurrence chain.

### Stale-write trigger

`{table}_reject_stale` on each Postgres table: an incoming row whose
`updated_at` is not strictly newer than the stored one is skipped silently, without failing the
batch. It is why the push needs no dirty flag. See
[Sync wire format](sync-wire.md#stale-write-trigger).

### Store

One of the storage [ports](#port) — `TaskStore`, `ProjectStore`, `SectionStore`,
`TagStore`, `AttachmentStore`, `BackupStore`, `SyncStore` — each with exactly one SQLDelight
[adapter](#adapter). Tests never fake them.

### StoreTransaction

The seam that makes several store writes commit together:
`storeTransaction.run { … }` runs a block against a non-suspending `TransactionScope` inside one
SQLDelight transaction. Used by completing, reopening and deleting.

### Subtask

A task with a `parentId`: one level deep, sharing its parent's project, deleted and
completed with it.

### Successor

The row inserted when a recurring occurrence is completed. Its id is derived
(`UuidV7.successorId`), so two devices completing the same occurrence offline agree on it.
Reopening the completed occurrence deletes a successor nobody has completed yet.

### Sweep

Collecting tombstones older than 90 days: on the server with one `DELETE` per table,
then locally, at most once a day after a successful round. Also `neon/db.sh sweep` for
maintenance across accounts.

### Tag

A cross-cutting label (`Tag`, `tagRow`). A task carries any number; membership is the
task's packed `tagIds` column, not a join table. See
[Tasks, projects and tags](../concepts/tasks-projects-tags.md).

### Tombstone

A deleted row that stays, with `deletedAt` set, so the delete can travel to other
devices. Every ordinary read hides it. See [Database](database.md#gotchas-checklist).

### Two-pane

The desktop layout at 1000 dp and wider: list on the left, [detail pane](#detail-pane)
on the right. Resizing across the threshold moves an open detail between pane and stack.

### Undo window

`CadenceViewModel.UNDO_WINDOW`, five seconds: how long `UndoSlot` holds a
[pending delete](#pending-delete) before committing it. Undo within it cancels the job and costs
no write.

### UndoSlot

`ui/undo/UndoSlot.kt`: the one snackbar slot, holding at most one undoable action
and queueing informational messages behind it.

### UUIDv7

The id format of every record, minted by the repository (`UuidV7.random()`), never by
storage. Time-ordered, so ids sort roughly by creation.

### Watermark

`syncStateRow.pushWatermark`: the newest local `updatedAt` this device has pushed,
in its own clock. The next push sends everything above it. Compare [cursor](#cursor).

### Workspace

The desktop's window state — sidebar width and folding, window bounds — kept in
`workspace.json`, apart from the settings. See [Configuration](configuration.md#desktop-window-state).
