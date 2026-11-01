package com.pocketbudget.app

import android.app.Application
import com.pocketbudget.app.di.AppContainer

class PocketBudgetApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
