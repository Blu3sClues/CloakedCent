package com.pocketbudget.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pocketbudget.app.domain.model.TransactionType
import com.pocketbudget.app.ui.feature.account.AccountEditorRoute
import com.pocketbudget.app.ui.feature.account.AccountEditorViewModel
import com.pocketbudget.app.ui.feature.account.AdjustBalanceRoute
import com.pocketbudget.app.ui.feature.dashboard.DashboardRoute
import com.pocketbudget.app.ui.feature.history.HistoryRoute
import com.pocketbudget.app.ui.feature.history.HistoryViewModel
import com.pocketbudget.app.ui.feature.settings.SettingsRoute
import com.pocketbudget.app.ui.feature.budget.ARG_BUDGET_ID
import com.pocketbudget.app.ui.feature.budget.BudgetDetailRoute
import com.pocketbudget.app.ui.feature.budget.BudgetEditorRoute
import com.pocketbudget.app.ui.feature.budget.BudgetsRoute
import com.pocketbudget.app.ui.feature.recurring.ARG_SCHEDULE_ID
import com.pocketbudget.app.ui.feature.recurring.RecurringDetailRoute
import com.pocketbudget.app.ui.feature.recurring.RecurringEditorRoute
import com.pocketbudget.app.ui.feature.recurring.RecurringListRoute
import com.pocketbudget.app.ui.feature.recurring.UpcomingRoute
import com.pocketbudget.app.ui.feature.transaction.TransactionDetailRoute
import com.pocketbudget.app.ui.feature.transaction.TransactionEditorRoute
import com.pocketbudget.app.ui.feature.transaction.TransactionEditorViewModel

private object Routes {
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
    const val BUDGETS = "budgets"
    const val BUDGET = "budget/{$ARG_BUDGET_ID}"
    const val BUDGET_EDIT = "budget-edit?$ARG_BUDGET_ID={$ARG_BUDGET_ID}"
    const val RECURRING = "recurring"
    const val SCHEDULE = "recurring/{$ARG_SCHEDULE_ID}"
    const val SCHEDULE_EDIT = "recurring-edit?$ARG_SCHEDULE_ID={$ARG_SCHEDULE_ID}"
    const val UPCOMING = "upcoming"

    fun budget(id: Long) = "budget/$id"
    fun budgetEdit(id: Long?) = "budget-edit?$ARG_BUDGET_ID=${id ?: -1}"
    fun schedule(id: Long) = "recurring/$id"
    fun scheduleEdit(id: Long?) = "recurring-edit?$ARG_SCHEDULE_ID=${id ?: -1}"
    const val ACCOUNT = "account?${AccountEditorViewModel.ARG_ACCOUNT_ID}={${AccountEditorViewModel.ARG_ACCOUNT_ID}}"
    const val HISTORY = "history?${HistoryViewModel.ARG_ACCOUNT_ID}={${HistoryViewModel.ARG_ACCOUNT_ID}}"
    const val NEW_TRANSACTION = "transaction/new?${TransactionEditorViewModel.ARG_TYPE}={${TransactionEditorViewModel.ARG_TYPE}}"
    const val TRANSACTION = "transaction/{${TransactionEditorViewModel.ARG_TRANSACTION_ID}}"
    const val EDIT_TRANSACTION = "transaction/{${TransactionEditorViewModel.ARG_TRANSACTION_ID}}/edit"

    const val ADJUST_BALANCE = "account/{${AccountEditorViewModel.ARG_ACCOUNT_ID}}/adjust"

    fun adjustBalance(accountId: Long) = "account/$accountId/adjust"
    fun account(id: Long?) = "account?${AccountEditorViewModel.ARG_ACCOUNT_ID}=${id ?: -1}"
    fun history(accountId: Long?) = "history?${HistoryViewModel.ARG_ACCOUNT_ID}=${accountId ?: -1}"
    fun newTransaction(type: TransactionType) = "transaction/new?${TransactionEditorViewModel.ARG_TYPE}=${type.name}"
    fun transaction(id: Long) = "transaction/$id"
    fun editTransaction(id: Long) = "transaction/$id/edit"
}

@Composable
fun PocketBudgetNavHost() {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    val optionalId = { name: String -> navArgument(name) { type = NavType.LongType; defaultValue = -1L } }
    val requiredId = navArgument(TransactionEditorViewModel.ARG_TRANSACTION_ID) { type = NavType.LongType }

    NavHost(nav, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) {
            DashboardRoute(
                onAddTransaction = { nav.navigate(Routes.newTransaction(it)) },
                onAddAccount = { nav.navigate(Routes.account(null)) },
                onOpenAccount = { nav.navigate(Routes.history(it)) },
                onOpenTransaction = { nav.navigate(Routes.transaction(it)) },
                onOpenHistory = { nav.navigate(Routes.history(null)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenBudgets = { nav.navigate(Routes.BUDGETS) },
                onOpenRecurring = { nav.navigate(Routes.RECURRING) },
                onOpenUpcoming = { nav.navigate(Routes.UPCOMING) },
            )
        }
        composable(Routes.ACCOUNT, listOf(optionalId(AccountEditorViewModel.ARG_ACCOUNT_ID))) {
            AccountEditorRoute(
                onDone = back,
                // The archived account's history screen is no longer meaningful.
                onArchived = { nav.popBackStack(Routes.DASHBOARD, inclusive = false) },
                onAdjustBalance = { nav.navigate(Routes.adjustBalance(it)) },
            )
        }
        composable(Routes.ADJUST_BALANCE, listOf(navArgument(AccountEditorViewModel.ARG_ACCOUNT_ID) { type = NavType.LongType })) {
            AdjustBalanceRoute(onDone = back)
        }
        composable(Routes.HISTORY, listOf(optionalId(HistoryViewModel.ARG_ACCOUNT_ID))) {
            HistoryRoute(
                onBack = back,
                onOpen = { nav.navigate(Routes.transaction(it)) },
                onEditAccount = { nav.navigate(Routes.account(it)) },
            )
        }
        composable(Routes.SETTINGS) { SettingsRoute(onBack = back) }
        composable(Routes.BUDGETS) {
            BudgetsRoute(onBack = back, onOpen = { nav.navigate(Routes.budget(it)) }, onAdd = { nav.navigate(Routes.budgetEdit(null)) })
        }
        composable(Routes.BUDGET, listOf(navArgument(ARG_BUDGET_ID) { type = NavType.LongType })) {
            BudgetDetailRoute(onBack = back, onEdit = { nav.navigate(Routes.budgetEdit(it)) })
        }
        composable(Routes.BUDGET_EDIT, listOf(optionalId(ARG_BUDGET_ID))) { BudgetEditorRoute(onDone = back) }
        composable(Routes.RECURRING) {
            RecurringListRoute(
                onBack = back,
                onOpen = { nav.navigate(Routes.schedule(it)) },
                onAdd = { nav.navigate(Routes.scheduleEdit(null)) },
                onUpcoming = { nav.navigate(Routes.UPCOMING) },
            )
        }
        composable(Routes.SCHEDULE, listOf(navArgument(ARG_SCHEDULE_ID) { type = NavType.LongType })) {
            RecurringDetailRoute(onBack = back, onEdit = { nav.navigate(Routes.scheduleEdit(it)) })
        }
        composable(Routes.SCHEDULE_EDIT, listOf(optionalId(ARG_SCHEDULE_ID))) { RecurringEditorRoute(onDone = back) }
        composable(Routes.UPCOMING) { UpcomingRoute(onBack = back, onOpenTransaction = { nav.navigate(Routes.transaction(it)) }) }
        composable(
            Routes.NEW_TRANSACTION,
            listOf(
                navArgument(TransactionEditorViewModel.ARG_TYPE) {
                    type = NavType.StringType
                    defaultValue = TransactionType.EXPENSE.name
                },
            ),
        ) { TransactionEditorRoute(onDone = back) }
        composable(Routes.TRANSACTION, listOf(requiredId)) {
            TransactionDetailRoute(onBack = back, onEdit = { nav.navigate(Routes.editTransaction(it)) })
        }
        composable(Routes.EDIT_TRANSACTION, listOf(requiredId)) { TransactionEditorRoute(onDone = back) }
    }
}
