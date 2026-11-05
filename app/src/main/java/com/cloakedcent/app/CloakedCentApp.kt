package com.cloakedcent.app

import android.app.Application
import com.cloakedcent.app.di.AppContainer
import com.cloakedcent.app.work.PlanningNotifier
import com.cloakedcent.app.work.PlanningScheduler

class CloakedCentApp : Application() {

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
