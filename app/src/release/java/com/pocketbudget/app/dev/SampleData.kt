package com.pocketbudget.app.dev

import com.pocketbudget.app.di.AppContainer

/** Release builds ship no sample data. */
object SampleData {
    @Suppress("UNUSED_PARAMETER")
    fun seeder(container: AppContainer): (suspend () -> Unit)? = null
}
