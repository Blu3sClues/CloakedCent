package com.pocketbudget.app

import android.app.Application
import com.pocketbudget.app.di.AppContainer
import com.pocketbudget.app.work.PlanningNotifier
import com.pocketbudget.app.work.PlanningScheduler

class PocketBudgetApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        PlanningNotifier.ensureChannels(this)
        // Generates due recurring occurrences now and keeps a periodic safety net.
        PlanningScheduler.schedule(this)
    }
}
