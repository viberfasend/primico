# Working notes: hard-won gotchas from agent sessions in this repo

Dated 2026-11-01, written after the #112 (blob store) and #40 (multi-select) sessions. These are
the things that cost real time and are not obvious from the code. When one stops being true, delete
the line — this file is notes, not law.

## Testing

- **JUnit 4 + `kotlinx-coroutines-test`, not Kotest/MockK.** The `kotlin-testing` skill describes
  Kotest, but this repo's suites are plain JUnit4 (`org.junit.Test`, `TemporaryFolder`,
  `assertEquals`/`assertThrows`) over the **real SQLDelight stores on in-memory SQLite**
  (`TestDatabase.kt`'s `TestStores`) — no mocking library, no fakes for storage.
- ViewModel tests live in `ui/src/jvmTest` (not `jvmSharedTest`) because the ViewModel takes a
  concrete `CadenceSyncEngine`. Build the ViewModel on `runTest`'s `backgroundScope` and subscribe
  to `state` — its `init` collectors never finish, and `stateIn(WhileSubscribed)` stays cold until
  read. `advanceUntilIdle` is an extension and needs an explicit
  `import kotlinx.coroutines.test.advanceUntilIdle`.
- RED-check pattern that works: `git stash push -- <production files>`, run only the new tests,
  watch them fail, `git stash pop`. Both #112 regression tests fail on the unfixed code this way.
- **File-structure trap:** `CadenceViewModelUndoTest.kt` ends with a second top-level class
  (`GatedStoreTransaction`) *after* the test class. Appending tests at EOF lands them inside that
  class and everything fails with "unresolved reference". Insert before the test class's closing
  brace.

## Compose (the version in `gradle/libs.versions.toml`)

- Inside `Modifier.pointerInput { }`, `awaitPointerEventScope { }` and `awaitPointerEvent(pass)`
  are **members of the scope** — no import; importing `...pointer.awaitPointerEventScope` does not
  resolve.
- Pointer-event modifier checks (`event.keyboardModifiers.isShiftPressed` etc.) are extensions in
  **`androidx.compose.ui.input.pointer`**. Importing `isShiftPressed` from `...input.key` binds to
  the `KeyEvent` extensions and fails with "receiver type mismatch".
- Generated resource accessors (`Res.string.x`, `Res.plurals.x`) are **extension properties** on
  `Res.string`/`Res.plurals`. `import de.andi1984.cadence.ui.resources.Res` alone does not resolve
  them — use `import ...resources.*` (the style of `UndoSlot.kt`/`RowMenus.kt`) or import the
  specific accessor.
- Strings rule: every user-visible string goes into **both**
  `ui/src/commonMain/composeResources/values/strings.xml` and `values-de/strings.xml`, plurals
  included. The accessors regenerate on build.

## Git / PR flow in this repo

- PR branches get **rebased remotely** (someone or a bot ran "Update branch" on #233). Before
  pushing after time away: `git fetch origin <branch>`, compare, and rebase with
  `git rebase --onto origin/<branch> <old-base> <branch>`.
- Trap: `git rebase --onto X <sha> HEAD` while on a branch leaves **detached HEAD** — the branch
  ref stays at the old commit and the next push is rejected non-fast-forward. After such a rebase,
  `git checkout <branch> && git reset --hard <rebased-commit>` before pushing.
- Never branch a feature off another feature branch: `git checkout -b feat/x` while on
  `fix/y` makes the PR contain both tickets. Rebase onto `origin/main` (`git rebase --onto
  origin/main <base> <branch>`) before pushing.

## Decisions and state worth knowing

- **ADR 0007** (`docs/adr/0007-blob-store-invariants.md`): before touching `BlobStore` or
  `reclaim`, read it — cancellation must unwind (never become `StoreResult.Failed`), and filing a
  row that names a hash vs reclaiming blobs are serialized by `CadenceRepository.blobGate`.
- **#40 shipped** (PR #234): multi-select lives in `RowSelectionState.selectedIds`, batches go
  through `CadenceRepository.applyToTasks(ids, TaskBatchAction)` in one transaction, the bar is
  `components/SelectionBar.kt`, drag payloads are `DragPayload.TaskDrag(tasks)` with id-list
  intents.
- Roadmap order after these: #48 adaptive shell (needs a new ADR), then planning surfaces #148.
  Tickets are GitHub issues with `priority:P0–P3` labels; "Now" = `ROADMAP.md`'s Now section.
