package de.andi1984.cadence.reminders

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.andi1984.cadence.MainActivity
import de.andi1984.cadence.R

/** Posts the reminder notification when a task's alarm fires. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(AlarmReminderScheduler.EXTRA_TASK_ID)
        val title = intent.getStringExtra(AlarmReminderScheduler.EXTRA_TITLE).orEmpty()
        if (taskId.isNullOrBlank() || title.isBlank()) return
        // Alarms armed before slots existed carry the lead minutes instead of a slot; they map to
        // the legacy slot of the same number, which is what their request code was keyed on.
        val slot = intent.getStringExtra(AlarmReminderScheduler.EXTRA_SLOT)
            ?: intent.getIntExtra(EXTRA_LEAD_MINUTES, 0).toString()

        val allowed = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AlarmReminderScheduler.EXTRA_TASK_ID, taskId)
        }
        val requestCode = ReminderRequestCodes.codeFor(context, taskId, slot)
        val contentIntent = PendingIntent.getActivity(
            context,
            requestCode,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Worded at arm time from the trigger ("Due in 2 days", "Due today", "In 10 minutes", …);
        // an alarm from before that carried only the lead falls back to the old wording.
        val contentText = intent.getStringExtra(AlarmReminderScheduler.EXTRA_CONTENT_TEXT)
            ?: if (slot == "0") {
                context.getString(R.string.reminder_content_text)
            } else {
                val lead = slot.toIntOrNull() ?: 0
                context.resources.getQuantityString(
                    R.plurals.reminder_lead_content_text,
                    lead,
                    lead,
                )
            }

        val notification = NotificationCompat.Builder(context, AlarmReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        NotificationManagerCompat.from(context).notify(requestCode, notification)
    }

    private companion object {
        /** Written by the pre-slot version of the scheduler. */
        const val EXTRA_LEAD_MINUTES = "leadMinutes"
    }
}
