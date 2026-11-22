package de.andi1984.cadence.desktop

import de.andi1984.cadence.data.CadenceCore
import de.andi1984.cadence.data.db.DatabaseDriverFactory
import de.andi1984.cadence.data.sync.CadenceSyncEngine
import de.andi1984.cadence.desktop.data.DesktopAttachmentOpener
import de.andi1984.cadence.desktop.data.DesktopBackupFilePicker
import de.andi1984.cadence.desktop.data.DesktopBackupIo
import de.andi1984.cadence.desktop.data.DesktopIcsGateway
import de.andi1984.cadence.desktop.data.DesktopReminderScheduler
import de.andi1984.cadence.desktop.data.DesktopSettingsStore
import de.andi1984.cadence.desktop.data.DesktopWeatherGateway
import de.andi1984.cadence.desktop.data.createReminderTrayIcon
import de.andi1984.cadence.desktop.platform.PlatformDirs
import de.andi1984.cadence.ui.ViewModelAdapters
import de.andi1984.cadence.ui.format.notificationLabel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.TrayIcon
import java.time.Duration

/** Hand-rolled dependency graph, same idea as `:app-android`'s `AppContainer`: the app is small
 *  enough not to need a DI framework. Lives for the whole process — Compose Desktop has no
 *  Application class to build it in, so [main] constructs one and keeps it alive.
 *
 *  The database, the blob store, the repository and the sync engine are built once, in
 *  [CadenceCore], for both shells; what is left here is the desktop's own adapters — a JSON
 *  settings file, `java.awt.Desktop`, `JFileChooser`, the tray icon poll — plus [close], the one
 *  exit path both `onCloseRequest` and the tray's Quit call. */
class AppContainer {

    private val dataDir = PlatformDirs.dataDir()

    val core = CadenceCore(
        driver = DatabaseDriverFactory(dataDir).createDriver(),
        dataDir = dataDir,
    )

    val repository = core.repository

    /** [CadenceCore.applicationScope], named here too — the desktop's writes (settings, the
     *  reminder scheduler) used to reach a field declared directly on this class. */
    val applicationScope = core.applicationScope

    /** On [applicationScope] because its writes are, and declared after it for the same reason —
     *  a field initialiser can only read what is already built. */
    val settingsStore = DesktopSettingsStore(dataDir, applicationScope)

    val backupIo = DesktopBackupIo(repository, settingsStore)

    val icsGateway = DesktopIcsGateway(repository)

    val backupFilePicker = DesktopBackupFilePicker()

    /** `java.awt.Desktop` where Android has an intent — the picker half lives in the composition,
     *  so only this one is a container singleton. */
    val attachmentOpener = DesktopAttachmentOpener()

    /** Same shape as `:app-android`'s, because sync is not a platform difference (ADR 0002,
     *  decision 7): the same class over the same database, on the process-wide scope. */
    val syncEngine = core.syncEngine

    /**
     * The tray icon, or null where there is no tray (a headless run, and several Linux desktops
     * that claim one and have none).
     *
     * Held here rather than created inside the scheduler because it is no longer only the
     * scheduler's: `main()` hangs the tray menu — show, new task, sync, quit — off the same icon,
     * and two icons would mean two Cadences in the tray.
     */
    val trayIcon: TrayIcon? = createReminderTrayIcon()

    val reminderScheduler = DesktopReminderScheduler(
        scope = applicationScope,
        trayIcon = trayIcon,
        // The balloon's content line, worded from the trigger exactly as the Android notification
        // words it. The poll fires from a coroutine, not composition, so it reads the resources
        // through the suspend accessors rather than `stringResource`.
        labelFor = { title, source -> runBlocking { notificationLabel(title, source) } },
    )

    /** The weather call for Today's outdoor section — `:core`'s client, since HTTPS+JSON is not a
     *  platform difference; a port only so the ViewModel's tests can fake it (ADR 0007). */
    val weatherGateway = DesktopWeatherGateway()

    /** Handed to [de.andi1984.cadence.ui.cadenceViewModel] by `main()` so the eight-argument
     *  `CadenceViewModel` constructor is written once, in `:ui`. */
    val viewModelAdapters = ViewModelAdapters(
        settingsStore = settingsStore,
        reminderScheduler = reminderScheduler,
        backupGateway = backupIo,
        attachmentOpener = attachmentOpener,
        weatherGateway = weatherGateway,
        icsGateway = icsGateway,
    )

    /**
     * The one exit path (CLAUDE.md, "SettingsStore's setters are not suspend"): both
     * `onCloseRequest` and the tray menu's Quit call this, in the order that rule requires —
     * a last sync round, run with a bound rather than launched, then the settings flush
     * `exitApplication()` must not race, then [CadenceCore.close] to cancel the application
     * scope and close the database driver.
     *
     * The last round is *run*, not launched: `syncInBackground()` would hand it to the very
     * application scope [CadenceCore.close] cancels a moment later, so the round died at its
     * first suspension point and never sent anything — a change made just before quitting
     * reached the phone only on the next start (#216). The sequence itself lives in the
     * top-level [shutdown] so a test can drive it against a fake sync client.
     */
    fun shutdown() = shutdown(
        syncEngine = syncEngine,
        flushSettings = settingsStore::flush,
        closeCore = core::close,
    )
}

/**
 * The exit sequence, with its parts as parameters: one bounded sync round, then the settings
 * flush, then the core's teardown — in that order, because the round needs the application scope
 * and the database that the teardown cancels and closes.
 *
 * The round runs on the calling thread under [runBlocking], bounded by [lastSyncTimeout] so a
 * server that never answers cannot hold the window open: launched on the application scope
 * instead, it would be cancelled by [closeCore] at its first suspension point before sending
 * anything (#216). Signed out or unconfigured a round returns before making a request anyway;
 * the [CadenceSyncEngine.isSignedIn] gate keeps the quit path from even taking the round's
 * mutex when there is no account.
 */
internal fun shutdown(
    syncEngine: CadenceSyncEngine,
    flushSettings: () -> Unit,
    closeCore: () -> Unit,
    lastSyncTimeout: Duration = LAST_SYNC_ON_QUIT_TIMEOUT,
) {
    runBlocking {
        if (syncEngine.isSignedIn()) {
            withTimeoutOrNull(lastSyncTimeout.toMillis()) { syncEngine.syncOnce() }
        }
    }
    flushSettings()
    closeCore()
}

/** How long the last round on the way out may hold the window open — the bound that keeps quit
 *  from hanging on a server that never answers. */
private val LAST_SYNC_ON_QUIT_TIMEOUT: Duration = Duration.ofSeconds(3)
