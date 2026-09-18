---
title: Your first contribution
description: A worked exercise that adds a small derived view to CadenceUiState, pins it with JVM tests, runs the CI command, and opens a pull request.
sidebar:
  order: 3
---

This tutorial walks you through a whole change the way the project expects it: test first,
the smallest implementation, the full suite, a Conventional Commit, and a pull request.

:::note[This is an exercise]
The feature built here, `CadenceUiState.unscheduledTasks()`, **does not exist** in Primico. It's
the vehicle for learning the workflow. When you're done, delete the branch (the last step shows
how). For a real contribution, pick an issue or open one first, as
[CONTRIBUTING.md](../../CONTRIBUTING.md) asks.
:::

You'll need the setup from the [quickstart](quickstart.md): a clone, JDK 17 and a passing
test run.

## The feature

Primico's views are all about dates: Today, Upcoming and the overdue block. An open task with no
due date only ever shows up in its container (the Inbox or a project) and is easy to lose. We'll
add a **derived view** that answers "what open work has no date at all?"

Derived views belong on
[`CadenceUiState`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/CadenceViewModel.kt), the
single state object every screen receives. They are plain functions on a data class, so testing
one needs no ViewModel, no flows and no dispatcher.

The rules we want:

- open tasks only;
- no `dueDate`;
- root tasks only, because a subtask is spoken for by its parent, the same rule the Inbox follows.

## 1. Branch from `main`

```bash
git switch main
git pull
git switch -c feat/unscheduled-tasks
```

If you're working from a fork, clone the fork instead and add the upstream as a remote. The
branch name is free text, but `feat/…` and `fix/…` read well.

## 2. Write the test first

The derivation tests live in
[`ui/src/jvmSharedTest/.../CadenceUiStateTest.kt`](../../ui/src/jvmSharedTest/kotlin/de/andi1984/cadence/ui/CadenceUiStateTest.kt).
`jvmSharedTest` is compiled twice, once for the desktop JVM (`:ui:jvmTest`) and once for Android
(`:ui:testDebugUnitTest`), so one test covers both targets.

The file already has a `task(...)` helper that builds a `Task` from a few named fields, and an
`ids()` helper. Add this test to the class, next to the other `rootTasks` tests:

```kotlin
// ── unscheduledTasks ─────────────────────────────────────────────────────────────

@Test
fun `unscheduledTasks is open root work with no due date`() {
    val state = CadenceUiState(
        tasks = listOf(
            task("someday"),
            task("dated", due = today),
            task("finished", done = true),
            task("step", parentId = "someday"),
        ),
    )

    assertEquals(listOf("someday"), state.unscheduledTasks().ids())
}
```

The state is a literal: four tasks, one of which should survive. This is the house style for
derivation tests. Don't mock anything; build the value you mean.

## 3. Watch it fail

Run only this test class. Test method names are backticked sentences, so the filter takes a
wildcard:

```bash
./gradlew :ui:jvmTest --tests "de.andi1984.cadence.ui.CadenceUiStateTest"
```

It doesn't compile yet, which is the first kind of red:

```
e: file:///…/ui/src/jvmSharedTest/kotlin/de/andi1984/cadence/ui/CadenceUiStateTest.kt:…: Unresolved reference 'unscheduledTasks'.

BUILD FAILED in 9s
```

## 4. Make it pass

Open [`ui/src/jvmShared/.../CadenceViewModel.kt`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/CadenceViewModel.kt)
and find `CadenceUiState`. Next to `inboxTasks()`, add:

```kotlin
/**
 * Open work nothing will ever put on Today or Upcoming: no due date, not done.
 *
 * Built on [rootTasks] rather than [tasks] for the Inbox's reason — a parent speaks for its
 * steps — which also drops a recurring occurrence its successor has replaced.
 */
fun unscheduledTasks(): List<Task> = rootTaskList.filter { !it.isDone && it.dueDate == null }
```

`rootTaskList` is the private, lazily built list behind `rootTasks()`. Building on it means the
expensive part (removing superseded recurring occurrences) is computed once per state, however
many screens ask. Run the test again:

```bash
./gradlew :ui:jvmTest --tests "de.andi1984.cadence.ui.CadenceUiStateTest"
```

```
BUILD SUCCESSFUL in 7s
```

Passing tests print nothing; only failures are logged.

## 5. Add a test against real storage

The literal-state test proves the rule. A second test proves the rule holds for **what storage
actually returns**. In particular, a deleted task is a tombstone in SQLite and must never reach
the list.

This project never fakes storage. Tests that touch it run the real SQLDelight stores over an
in-memory SQLite database. In `:ui` that's
[`TestStores`](../../ui/src/jvmSharedTest/kotlin/de/andi1984/cadence/ui/TestDatabase.kt), which
wires every store to one database and builds a real `CadenceRepository` on top. Add two imports
to the test file:

```kotlin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
```

and this test:

```kotlin
@Test
fun `a deleted task never reaches unscheduledTasks`() = runTest {
    val stores = TestStores()
    val repository = stores.repository()
    stores.seedTasks(task("keep"), task("gone"))

    repository.deleteTask("gone")
    val state = CadenceUiState(tasks = repository.tasks.first())

    assertEquals(listOf("keep"), state.unscheduledTasks().ids())
}
```

What happens here: `seedTasks` inserts through the real `SqlDelightTaskStore`,
`repository.deleteTask` stamps a tombstone (it never runs `DELETE`), and `repository.tasks` is the
same query every screen reads. It filters tombstones in SQL, so this test pins the whole path
rather than a fake's idea of it. `TestStores` runs everything on `Dispatchers.Unconfined`, so no
work escapes `runTest`'s scheduler.

Run the class again and it passes.

:::caution
If you ever write a test that hands a class its own `CoroutineScope` (a ViewModel, a scheduler,
anything with a polling loop), take it from `runTest`'s `backgroundScope`. A scope of your own
makes the test hang forever instead of failing. [Run the tests](../how-to/run-tests.md) explains
why.
:::

## 6. Run what CI runs

One invocation, exactly as CI runs it:

```bash
./gradlew :core:testDebugUnitTest :ui:testDebugUnitTest \
          :core:jvmTest :ui:jvmTest :app-desktop:test :ui:compileKotlinJvm
```

```
BUILD SUCCESSFUL in 38s
```

This runs your new tests twice, on the JVM and on the Android unit-test runtime. It also compiles
every screen for the JVM (`:ui:compileKotlinJvm`), which catches desktop breakage the Android
build can't see.

## 7. Commit with a Conventional Commit subject

The commit subject isn't only for humans. `.github/scripts/next-version.sh` reads the subjects
since the last tag to decide the next version: `feat` bumps the minor version, a `!` or a
`BREAKING CHANGE:` footer bumps the major, and everything else bumps the patch. This change adds
behaviour, so it's a `feat`:

```bash
git add ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/CadenceViewModel.kt \
        ui/src/jvmSharedTest/kotlin/de/andi1984/cadence/ui/CadenceUiStateTest.kt
git commit -m "feat(ui): derive the unscheduled tasks on CadenceUiState"
```

Scopes are free text. The module or feature name works well (`ui`, `sync`, `quick-add`).

## 8. Open a pull request

```bash
git push -u origin feat/unscheduled-tasks
gh pr create --base main
```

`gh` opens your editor with the repository's
[pull request template](../../.github/PULL_REQUEST_TEMPLATE.md): a summary, the related issue, and
a checklist (Conventional Commits, tests, the CI command, German strings for every new English
one). CI runs the same Gradle command you just ran, and `main` requires it to pass.

:::tip
A derivation that no screen reads is dead code, and a real pull request would pair it with the
screen that uses it. That's also where the next lessons are: every label goes through
`Res.string` with an English **and** a German entry
([localise a string](../how-to/localise.md)), and screens get the state and callbacks, never the
repository.
:::

## 9. Clean up the exercise

```bash
gh pr close feat/unscheduled-tasks --delete-branch   # if you opened one
git switch main
git branch -D feat/unscheduled-tasks
```

## What you learned

- Derived views go on `CadenceUiState`, built on its lazy indexes, not in screens.
- Derivation tests are literal states. Storage tests use the real stores through `TestStores`.
- `:ui:jvmTest --tests "…"` runs one class; the CI command runs everything in one invocation.
- The commit subject decides the version number.

Next steps: [add a repository mutation](../how-to/add-a-mutation.md), or read the
[testing philosophy](../concepts/testing-philosophy.md) for why nothing here is mocked.
