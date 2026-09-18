# Home-screen widgets

Loaded when working under `:app-android`. Everything else about the Android shell is in the root
`CLAUDE.md`; this file holds only the widget rules, which are Android-only and long.

- **Home-screen widgets are Glance, live in `:app-android/widget/`, and are Android-only.** Glance
  is the only widget toolkit still under development — `RemoteViews` is the legacy API it hides —
  and it has no desktop counterpart, so nothing about widgets belongs in `:ui`. `AppContainer`
  reconciles them on every `repository.tasks` emission exactly as reminders are reconciled, and
  `WidgetUpdater` is the single list of what exists; `updatePeriodMillis` in each provider-info
  XML is only the fallback for when no process is alive to run that collector. A widget never
  re-derives what to show: `TaskListScope` is a `TaskView` and two strings, and the widget builds
  a partial `CadenceUiState` (`widgetUiState()`) purely to call `taskList` on it, so the home
  screen shows exactly what the screen it mirrors shows — the user's `showCompleted` and
  `sortMode` included. The model to hold in mind is that **a widget is drawn by a *session* — a
  WorkManager job Glance starts, keeps for about 45 seconds after the first frame, and closes,
  composition and all** — and that the process is cold for most of them: a tap from the home
  screen, a reboot, an APK install, the half-hourly system tick. Every rule below follows from
  that, and each one cost real time to find:
  - **Frame one is drawn from a snapshot read before `provideContent`, and every later frame from
    the flow collected inside it — both, never one.** `updateAll` on an *open* session recomposes
    what it has and does not run `provideGlance` again, so a `first()` captured above
    `provideContent` alone froze the widget for 45 seconds — a task ticked off from it wrote,
    redrew, and changed nothing. `updateAll` on a *closed* session starts a new one and runs
    `provideGlance` again, so a `collectAsState(initial = null)` alone published a header-only
    (or, for the next-task widget, blank) first frame on every cold update and filled it in a
    frame later — when WorkManager, the database and the recomposer's next tick all got there
    before the process was taken, which on a phone is "usually". `widgetSnapshot()` +
    `widgetUiState(container, initial)` is the pair, and `widget/CadenceStateWidget.kt`'s
    `final override suspend fun provideGlance` is now the *one* place it runs — `TaskListWidget`
    and `CadenceNextTaskWidget` each used to write this method out by hand and only ever
    override `Content(context, state, today)`, so a widget can no longer read one half of the
    pair without the other.
  - **The list widgets scroll, and a scrolling widget is a `ListView` — its rows are RemoteViews
    collection items, and only `actionStartActivity` reliably escapes one.** A collection item
    owns no `PendingIntent`: the platform offers one template on the list plus a per-item fill-in
    intent, the template starts an activity, and everything else — `actionRunCallback` included —
    rides a trampoline that silently never arrived on a real device. Ticking a row off therefore
    goes through `WidgetToggleActivity`, an invisible `Theme.NoDisplay` activity that writes and
    finishes in `onCreate`. The next-task widget is *not* a collection, so its circle takes the
    better route — an `actionRunCallback` broadcast to `ToggleTaskCallback`, no window at all;
    both doors call the same `toggleTaskFromWidget`. Below Android 12 a collection's items are
    served from an in-memory store that dies with the process, which is the second reason frame
    one must already carry the list. Known, accepted cost: the scroll position resets when the
    list content changes.
  - **The design is bands and cards, derived — never invented — in the widget.** The rows come
    from `taskList` with their bands, so the Overdue/Today labels are the Today screen's own
    split made visible; the header counts open tasks and draws the day's progress (Today only —
    the Inbox is a place, not a plan); rows are rounded cards, overdue ones tinted with the error
    container, completion rings tinted by priority (`widgetPriorityColor`, from the same
    `CadenceColors` the app's `LocalCadenceColors` carries). Colour never stands alone: `P1`…`P4`
    is spelled out in the meta line and "Overdue" is written next to it. `cornerRadius` clips on
    Android 12+ and quietly draws square below.
  - **Two intents that differ only in their extras are the same intent.** `Intent.filterEquals` —
    what `PendingIntent` matches on — ignores extras, and Glance builds `actionStartActivity` with
    `FLAG_UPDATE_CURRENT`. A list of rows carrying nothing but a different `taskId` extra
    therefore collapses onto *one* `PendingIntent` whose extras the last row composed overwrote:
    every row opens the same task, and the widget reads as decorative. `WidgetIntents` gives each
    destination a distinct `data` URI, which is the only thing keeping them apart.
  - **A widget must visibly answer a tap.** Ticking a row honours the user's `showCompleted`
    setting like every screen does, so the row strikes through and stays instead of vanishing —
    a row that disappears reads as deleted, not completed.
  - **A widget keeps itself fresh, both directions** (ADR 0002, amendment 1). Outbound is the
    write aftercare below. Inbound: every widget session start runs `syncInBackgroundIfStale`
    (five minutes — the guard keeps sessions our own refreshes start from becoming requests),
    and while a task widget exists *and* a session is stored, `WidgetUpdater.refreshAll`
    keeps a 15-minute `SyncWorker` periodic alive — the desktop's poll interval, applied to the
    one surface that is always visible — and cancels it when either condition ends. Rows a round
    merges reach the widget through the container's collector like any other write.
  - **A write made from a widget does its own aftercare, and the aftercare lives in the write, not
    in its callers.** `AppContainer.toggleTaskFromWidget` — the write `ToggleTaskCallback` and
    `WidgetToggleActivity` both call, on a broadcast or an Activity, either way on a process
    nothing keeps alive — calls `WidgetUpdater.refreshAll` and hands the push to the server to
    `sync/SyncWorker` itself, sequenced after `setCompleted`'s transaction returns: a one-shot
    WorkManager job with a network constraint, the single WorkManager use in the app and not the
    poll ADR 0002 rejected (it never runs unprompted; it carries one write). Enqueuing ahead of
    the write races it — WorkManager runs a job with satisfied constraints immediately,
    in-process, so a round that won that race pushed everything above the watermark before the
    toggle was in it and did not fire again until the next one. The two call sites used to each
    repeat both calls in that order themselves; now they are one line with nothing to get wrong.
    In-app writes need neither: the container's collector redraws and the ViewModel's debounce
    pushes.
  - **Today turns over at midnight with nothing written**, so `WidgetMidnightRefresh` arms an
    inexact alarm from every `provideGlance` and every `refreshAll` while a task widget exists —
    the widget's version of the screen's midnight `LaunchedEffect` (#115).
