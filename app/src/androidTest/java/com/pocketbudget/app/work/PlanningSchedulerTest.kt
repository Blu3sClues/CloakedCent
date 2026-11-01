package com.pocketbudget.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanningSchedulerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val work = WorkManager.getInstance(context)

    @Test
    fun periodicGenerationIsScheduledExactlyOnce() {
        // The application schedules on start; repeating must not duplicate or reset it.
        PlanningScheduler.schedule(context)
        PlanningScheduler.schedule(context)
        val periodic = work.getWorkInfosForUniqueWork(PlanningScheduler.PERIODIC_WORK).get()
        assertEquals(1, periodic.size)
        assertTrue(periodic.single().state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING))
        assertEquals(12 * 60 * 60 * 1000L, periodic.single().periodicityInfo!!.repeatIntervalMillis)
    }

    @Test
    fun immediateRunIsUniqueToo() {
        PlanningScheduler.schedule(context)
        val immediate = work.getWorkInfosForUniqueWork(PlanningScheduler.IMMEDIATE_WORK).get()
        assertTrue(immediate.isNotEmpty())
        assertTrue("never more than one pending run", immediate.count { !it.state.isFinished } <= 1)
    }

    @Test
    fun notificationChannelsExist() {
        PlanningNotifier.ensureChannels(context)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(manager.getNotificationChannel(PlanningNotifier.CHANNEL_REMINDERS) != null)
        assertTrue(manager.getNotificationChannel(PlanningNotifier.CHANNEL_BUDGETS) != null)
    }
}
