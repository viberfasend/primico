package de.andi1984.cadence.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import de.andi1984.cadence.AppContainer
import de.andi1984.cadence.CadenceApplication
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.reminder.ReminderFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * Receives the alarm manager callback, shows the notification, and handles the two actions
 * on it — Done and Snooze — from the user. Actions are handled in their own short-lived
 * coroutine scope so that they can use suspend functions on the repository and the alarm
 * scheduler without blocking the broadcast receiver or leaking work after the receiver is
 * finished.
 */
class ReminderReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SHOW -> showNotification(context, intent)
            ACTION_DONE -> scope.launch {
                handleDone(context, intent)
            }
            ACTION_SNOOZE -> scope.launch {
                handleSnooze(context, intent)
            }
            else -> return
        }
    }

    /**
     * Create and show the reminder notification, in the channel that is created once
     * when the app starts ([AlarmReminderScheduler.createChannel]).
     */
    private fun showNotification(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE) ?: return

        val appContext = context.applicationContext as CadenceApplication
        val container = appContext.container
        val repository = container.repository

        // Fetch the task to get its title
        val task = runBlocking {
            repository.taskById(taskId)
        } ?: return

        val notificationManager = context.getSystemService(NotificationManager::class.java)

        val doneIntent = PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_DONE
                putExtra(EXTRA_TASK_ID, taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val snoozeIntent = PendingIntent.getBroadcast(
            context,
            taskId.hashCode() + 1,
            Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_SNOOZE
                putExtra(EXTRA_TASK_ID, taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, AlarmReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(taskTitle)
            .setContentText(context.getString(R.string.reminder_notification_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                context.getString(R.string.reminder_action_done),
                doneIntent,
            )
            .addAction(
                0,
                context.getString(R.string.reminder_action_snooze),
                snoozeIntent,
            )
            .build()

        notificationManager.notify(taskId.hashCode(), notification)
    }

    /**
     * Handle Done action: mark the task as completed and cancel its alarms.
     * Done must go through the repository's idempotent setCompleted path
     * (completeIfOpen), never a plain update.
     */
    private suspend fun handleDone(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val appContext = context.applicationContext as CadenceApplication
        val container = appContext.container
        val repository = container.repository
        val reminderScheduler = container.reminderScheduler

        // Fetch the task first, then mark it as completed
        val task = repository.taskById(taskId) ?: return
        repository.setCompleted(task, true)
        // Cancel all alarms for this task
        reminderScheduler.cancel(taskId)
    }

    /**
     * Handle Snooze action: set acknowledgedAt on the triggering reminder instant,
     * and schedule a new one-shot reminder at the snooze time.
     * This must survive the reconciler: a snoozed alarm must not be
     * cancelled on the next sync.
     *
     * The key is to set acknowledgedAt on the reminder that triggered, which tells the
     * planner to skip it, and add a new At reminder for the snooze time. The reconciler
     * will keep the new alarm because it's in the task's reminder list.
     */
    private suspend fun handleSnooze(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val appContext = context.applicationContext as CadenceApplication
        val container = appContext.container
        val repository = container.repository
        val reminderScheduler = container.reminderScheduler

        val task = repository.taskById(taskId) ?: return
        val now = Instant.now()
        val snoozeInstant = now.plusSeconds(SNOOZE_MINUTES * 60L)

        // Build the updated reminders list: acknowledge the one that triggered, add snooze reminder
        val snoozeReminder = de.andi1984.cadence.domain.reminder.Reminder(
            trigger = de.andi1984.cadence.domain.reminder.ReminderTrigger.At(
                dateTime = java.time.LocalDateTime.ofInstant(snoozeInstant, java.time.ZoneId.systemDefault())
            )
        )

        // Find and acknowledge the reminder that triggered
        val updatedReminders = task.reminders.map { reminder ->
            val triggerInstant = reminder.triggerAt
            
            // If this reminder triggered around now (within grace period), acknowledge it
            if (triggerInstant != null && 
                now.isAfter(triggerInstant.minusSeconds(GRACE_SECONDS)) &&
                now.isBefore(triggerInstant.plusSeconds(GRACE_SECONDS))) {
                reminder.copy(acknowledgedAt = now)
            } else {
                reminder
            }
        } + snoozeReminder

        // Update the task with all reminders (acknowledged ones + new snooze)
        repository.upsertTask(task.copy(reminders = updatedReminders))

        // Resync the scheduler so it picks up the new snooze reminder
        val updatedTask = repository.taskById(taskId) ?: return
        reminderScheduler.sync(listOf(updatedTask), emptyList(), true)
    }

    companion object {
        const val ACTION_SHOW = "de.andi1984.cadence.reminders.SHOW"
        const val ACTION_DONE = "de.andi1984.cadence.reminders.DONE"
        const val ACTION_SNOOZE = "de.andi1984.cadence.reminders.SNOOZE"

        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_TITLE = "task_title"

        // Snooze duration in minutes
        const val SNOOZE_MINUTES = 5L

        // Grace period in seconds for matching a triggered reminder
        const val GRACE_SECONDS = 60L
    }
}

// Extension to get triggerAt from Reminder
val de.andi1984.cadence.domain.reminder.Reminder.triggerAt: java.time.Instant?
    get() = when (val t = trigger) {
        is de.andi1984.cadence.domain.reminder.ReminderTrigger.At -> {
            java.time.Instant.from(t.dateTime.atZone(java.time.ZoneId.systemDefault()))
        }
        else -> null
    }
