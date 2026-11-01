package com.pocketbudget.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pocketbudget.app.MainActivity
import com.pocketbudget.app.PocketBudgetApp
import com.pocketbudget.app.R
import com.pocketbudget.app.core.money.MoneyFormatter
import com.pocketbudget.app.domain.model.BudgetProgress
import com.pocketbudget.app.domain.model.ReminderPlanner
import com.pocketbudget.app.domain.model.TransactionType
import com.pocketbudget.app.domain.repository.Reminder
import com.pocketbudget.app.domain.repository.StorageUnavailableException
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.TimeUnit

/**
 * Generates due recurring occurrences, then posts reminders and budget alerts.
 * Everything it does is idempotent, so WorkManager may run it at any time,
 * repeatedly, after reboots, or after time zone changes (dates are local
 * calendar dates evaluated at run time).
 */
class PlanningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as PocketBudgetApp).container
        return try {
            container.storageRepository.openStorage()
            val today = LocalDate.now()
            container.recurringRepository.generateDue(today)
            val notifier = PlanningNotifier(applicationContext)
            // Only claim (mark as sent) what can actually be shown.
            if (notifier.canNotify()) {
                container.recurringRepository.claimReminders(today).forEach(notifier::showReminder)
                container.budgetRepository.claimNewAlerts(today).forEach { (progress, threshold) ->
                    notifier.showBudgetAlert(progress, threshold)
                }
            }
            // Image files left by an interrupted import or discard.
            container.receiptRepository.removeOrphanImages()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageUnavailableException) {
            // A missing or damaged key will not fix itself; never retry into data loss.
            if (e.reason == StorageUnavailableException.Reason.KEYSTORE_UNAVAILABLE) Result.retry() else Result.failure()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

object PlanningScheduler {
    const val PERIODIC_WORK = "planning-periodic"
    const val IMMEDIATE_WORK = "planning-now"

    /** Twice-daily safety net; KEEP so app restarts do not reset the cadence. */
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PlanningWorker>(12, TimeUnit.HOURS).build(),
        )
        work.enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<PlanningWorker>().build())
    }
}

class PlanningNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)
    private val money = MoneyFormatter()

    fun canNotify(): Boolean =
        manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun showReminder(reminder: Reminder) {
        val date = reminder.dueDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        val title = context.getString(
            when {
                reminder.kind == ReminderPlanner.Kind.OVERDUE -> R.string.notify_overdue_title
                reminder.type == TransactionType.INCOME -> R.string.notify_income_due_title
                else -> R.string.notify_payment_due_title
            },
            reminder.title,
        )
        post(CHANNEL_REMINDERS, (reminder.transactionId % Int.MAX_VALUE).toInt(), title, "${money.format(reminder.amount.abs())} · $date")
    }

    fun showBudgetAlert(progress: BudgetProgress, threshold: Int) {
        val current = progress.current ?: return
        val title = context.getString(R.string.notify_budget_title, progress.budget.name, threshold)
        val body = context.getString(R.string.notify_budget_body, money.format(current.spent), money.format(current.allocated))
        post(CHANNEL_BUDGETS, 1_000_000 + (progress.budget.id * 1000 + threshold).toInt(), title, body)
    }

    private fun post(channel: String, id: Int, title: String, body: String) {
        ensureChannels(context)
        if (!canNotify()) return
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Lock screen shows only a generic line; names and amounts stay private.
        val public = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notify_public_text))
            .build()
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post: nothing to show.
        }
    }

    companion object {
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_BUDGETS = "budget_alerts"

        fun ensureChannels(context: Context) {
            val system = context.getSystemService(NotificationManager::class.java) ?: return
            system.createNotificationChannel(
                NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT),
            )
            system.createNotificationChannel(
                NotificationChannel(CHANNEL_BUDGETS, context.getString(R.string.channel_budgets), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}
