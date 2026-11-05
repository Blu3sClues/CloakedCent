package com.cloakedcent.app.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.cloakedcent.app.R

/** The sections reachable from the Material 3 navigation bar, in display order. */
enum class TopLevelDestination(val route: String, @StringRes val label: Int, @DrawableRes val icon: Int) {
    HOME(Routes.DASHBOARD, R.string.nav_home, R.drawable.ic_home),
    ANALYSIS(Routes.ANALYSIS, R.string.analysis_tab_overview_short, R.drawable.ic_bar_chart),
    RECEIPTS(Routes.RECEIPTS, R.string.receipts_title, R.drawable.ic_receipt),
    BUDGETS(Routes.BUDGETS, R.string.budgets_title, R.drawable.ic_wallet),
    RECURRING(Routes.RECURRING, R.string.recurring_title, R.drawable.ic_repeat),
    ;

    companion object {
        fun of(destination: NavDestination?): TopLevelDestination? =
            destination?.hierarchy?.firstNotNullOfOrNull { d -> entries.firstOrNull { it.route == d.route } }
    }
}

/**
 * Switches sections the standard way: one back-stack entry per section, state saved and
 * restored when returning, and Back from any section leads to Home.
 */
fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun CloakedCentNavigationBar(current: TopLevelDestination, onSelect: (TopLevelDestination) -> Unit) {
    NavigationBar {
        TopLevelDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == current,
                onClick = { if (destination != current) onSelect(destination) },
                icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                label = { Text(stringResource(destination.label)) },
            )
        }
    }
}
