package com.pocketbudget.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pocketbudget.app.ui.feature.dashboard.DashboardRoute
import com.pocketbudget.app.ui.theme.PocketBudgetTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PocketBudgetTheme {
                DashboardRoute()
            }
        }
    }
}
