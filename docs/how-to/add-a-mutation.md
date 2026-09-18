---
title: Add a repository mutation
description: Add a new write to CadenceRepository so it syncs, commits atomically when it spans stores, reaches the UI through the ViewModel, can be undone when it destroys something, and cancels the alarms of whatever it deletes.
sidebar:
  order: 3
---

Every change a user makes to their data goes through one class,
[`CadenceRepository`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/data/CadenceRepository.kt).
A new mutation has four obligations: it must **announce itself to sync**, **commit as one unit**
when it touches several stores, reach the UI **through the ViewModel**, and, if it deletes
anything, be **undoable** and **cancel reminders**. This guide goes through each one.

The running example is a hypothetical `renameTask(task, title)` that refuses a blank title.

## Prerequisites

- You know where the data flows: [the tour of the code](../tutorials/tour-of-the-code.md).
- You can [run the tests](run-tests.md).

## Steps

### 1. Route the write through `write { }`

`write { }` is the private wrapper that runs a block and then ticks `localWrites`. The
ViewModel's two-second sync debounce collects that tick. A mutation that skips it saves locally
and **never syncs**: the other devices don't hear about it until some unrelated write happens to
push the row.

For a mutation that can't fail, wrap the whole body, as `setDueDate` does:

```kotlin
suspend fun setDueDate(task: Task, dueDate: LocalDate?): Unit = write {
    taskStore.update(task.copy(dueDate = dueDate, updatedAt = now()))
}
```

Two details in that one line matter everywhere:

- **Stamp `updatedAt = now()`.** Last-writer-wins compares `updatedAt`, so an edit without a new
  stamp loses to the server's copy and gets overwritten on the next pull. `now()` reads the
  injected `Clock` and truncates to milliseconds. Never call `Instant.now()` directly.
- **Write through a store port.** Never write SQL from the repository.

### 2. Tick only on success when the result is a `RepositoryResult`

A mutation that validates returns `RepositoryResult` (`Success`, `Error`, `ValidationError`) and
wraps **only the store call on the success path**. A refused write must not wake sync:

```kotlin
suspend fun renameTask(task: Task, title: String): RepositoryResult<Unit> {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return RepositoryResult.ValidationError
    return try {
        write { taskStore.update(task.copy(title = trimmed, updatedAt = now())) }
        RepositoryResult.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RepositoryResult.Error("Failed to rename task", e)
    }
}
```

Rethrowing `CancellationException` keeps coroutine cancellation working. `upsertProject`,
`upsertSection` and `upsertTag` have the same shape.

:::note[Two exceptions to the rule]
**Attachment** mutations never call `write { }`. An attachment is not in the wire shape or the
backup file, so there's nothing for a round to push. And **pulls** never go through the
repository at all (`SqlDelightSyncStore.mergeAndAdvance` writes straight to the database), which
is what stops two devices waking each other forever. Don't route merged rows through a repository
method.
:::

### 3. Use one `StoreTransaction` when the write spans stores or steps

If the mutation is a chain of writes that only makes sense as a unit (close a row *and* insert
its successor, delete attachments *and* tombstone the task), run the chain inside
`storeTransaction.run { … }`. Otherwise a process killed between two steps leaves a half-done
state. `deleteTask` is the model:

```kotlin
suspend fun deleteTask(id: String): Unit = write {
    val hashes = storeTransaction.run {
        val ids = listOf(id) + subtasksOf(id).map { it.id }
        val hashes = hashesForTasks(ids)
        deleteAttachmentsForTasks(ids)
        tombstoneTaskWithSubtasks(id, now())
        hashes
    }
    reclaim(hashes)
}
```

The block runs against a
[`TransactionScope`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/data/Stores.kt) whose
members are plain, **non-suspending** functions. That's deliberate: SQLDelight's transaction
callback isn't `suspend`, and a store call that really suspended would land its write outside the
transaction. If your chain needs an operation `TransactionScope` doesn't have, add it there and
implement it in `SqlDelightTransactionScope` in
[`SqlDelightStores.kt`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/data/db/SqlDelightStores.kt).

Keep work that SQL can't roll back (filesystem cleanup like `reclaim`) **outside** the
transaction, and make it idempotent.

Two more storage rules apply to any new query you add:

- **Deleting is a tombstone.** Stamp `deletedAt` and `updatedAt`, and never `DELETE`, or the
  other device puts the row back. Guard with `deletedAt IS NULL` so a second delete doesn't
  restamp.
- **Chunk `IN :list` queries at `SQL_VARIABLE_LIMIT`** (900). Older Android SQLite allows 999
  bind parameters, and no test reproduces the crash.

### 4. Expose it on the ViewModel

Screens never see the repository. Add a callback to
[`CadenceViewModel`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/CadenceViewModel.kt)
that launches on its scope and turns failures into a snackbar. The text comes from a string
resource, never a Kotlin literal:

```kotlin
fun renameTask(task: Task, title: String) = scope.launch {
    when (val result = repository.renameTask(task, title)) {
        is RepositoryResult.Success -> Unit // the repository already ticked localWrites
        is RepositoryResult.Error -> showSnackbar(Res.string.snackbar_error, listOf(result.message))
        RepositoryResult.ValidationError -> showSnackbar(Res.string.snackbar_task_title_empty)
    }
}
```

`snackbar_task_title_empty` doesn't exist. Add it to both `strings.xml` files
([localise a string](localise.md)). Don't call `armSync()` or anything like it. The ViewModel
used to arm sync from each mutation, and that job now belongs to `write { }` alone.

### 5. If it destroys work, defer it behind the undo window

Deletes that make work disappear (a task, a project, completed Inbox tasks, the danger-zone wipe)
don't write immediately. The ViewModel hands
[`UndoSlot`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/undo/UndoSlot.kt) an
`UndoAction`. The slot hides those ids from every list at once (`CadenceUiState.pendingDeleteIds`),
shows the snackbar, and only calls `commitPendingDelete` after `UNDO_WINDOW` (five seconds). An
undo just cancels the job, so it costs no transaction. For a new destructive mutation:

- [ ] Add an `UndoAction` subclass whose `ids` are **every** row it will hide, subtasks included.
- [ ] Call `undoSlot.offer(action, count)` from the ViewModel method instead of the repository.
- [ ] Add a branch to `commitPendingDelete` that performs the repository write.

A write that moves or relabels work and makes nothing disappear writes straight through, like
`deleteSection` and `deleteTag`: nothing vanishes, so there's nothing for an undo to give back.

### 6. Cancel the alarms of anything it deletes

`ReminderScheduler.sync` only ever sees the tasks that still exist, so it **can't** cancel an
alarm for a task that's gone. A mutation that deletes tasks returns the ids it deleted, and the
ViewModel cancels each one:

```kotlin
repository.deleteProject(action.project.id, action.deleteTasks)
    .forEach { reminderScheduler.cancel(it) }
```

Return the ids the **repository** actually tombstoned, not a list the UI filtered. The repository
is the only one that knows the whole set, including subtasks and anything a pull merged in
meanwhile.

## Tests to add

All in `:core`'s
[`CadenceRepositoryTest`](../../core/src/jvmSharedTest/kotlin/de/andi1984/cadence/CadenceRepositoryTest.kt),
against the real stores (`TestStores`, never a fake):

- [ ] **The rule itself**: what the row looks like afterwards, read back with `taskStore.byId`.
  Pin `updatedAt` against the test's fixed `clock`.
- [ ] **The `localWrites` contract**, in the `── localWrites ──` section at the end of the file.
  Use its `collectTicks()` helper, which subscribes on `backgroundScope` before the call:

  ```kotlin
  @Test
  fun `renameTask ticks localWrites once`() = runTest {
      val task = store(Task(title = "Buy milk"))
      val ticks = collectTicks()

      repository.renameTask(task, "Buy oat milk")

      assertEquals(1, ticks.size)
  }

  @Test
  fun `a blank rename does not tick localWrites`() = runTest {
      val task = store(Task(title = "Buy milk"))
      val ticks = collectTicks()

      assertEquals(RepositoryResult.ValidationError, repository.renameTask(task, "  "))
      assertTrue(ticks.isEmpty())
  }
  ```

- [ ] **Atomicity**, if you used `storeTransaction`: `SqlDelightStoreTransactionTest` shows how
  a failure inside `run` rolls back every write in it.
- [ ] **The ViewModel side**, in `:ui`'s `CadenceViewModelCrudTest` (or
  `CadenceViewModelUndoTest` for a deferred delete). The ViewModel runs on `backgroundScope`, and
  a deferred delete is tested by advancing virtual time past `UNDO_WINDOW`. Assert
  `RecordingReminderScheduler.cancelled` for the alarm rule.

## Verify

```bash
./gradlew :core:jvmTest --tests "de.andi1984.cadence.CadenceRepositoryTest"
./gradlew :core:testDebugUnitTest :ui:testDebugUnitTest \
          :core:jvmTest :ui:jvmTest :app-desktop:test :ui:compileKotlinJvm
```

If you're signed in to your own Neon project, make the edit on one device and watch it arrive on
the other within about a minute (the foreground poll runs every 60 seconds). If it doesn't,
[debug sync](debug-sync.md).

## Related

- [Undo and deletes](../concepts/undo-and-deletes.md): tombstones, the undo window, and #114's
  two rules.
- [Sync](../concepts/sync.md): why `localWrites` and not the task flow arms the debounce.
- [Testing philosophy](../concepts/testing-philosophy.md): why the stores are never faked.
