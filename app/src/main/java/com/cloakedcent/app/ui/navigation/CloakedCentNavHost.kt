package com.cloakedcent.app.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cloakedcent.app.domain.model.TransactionType
import com.cloakedcent.app.ui.feature.account.AccountEditorRoute
import com.cloakedcent.app.ui.feature.account.AccountEditorViewModel
import com.cloakedcent.app.ui.feature.account.AdjustBalanceRoute
import com.cloakedcent.app.ui.feature.dashboard.DashboardRoute
import com.cloakedcent.app.ui.feature.history.HistoryRoute
import com.cloakedcent.app.ui.feature.history.HistoryViewModel
import com.cloakedcent.app.ui.feature.backup.BackupRoute
import com.cloakedcent.app.ui.feature.settings.AcknowledgementsRoute
import com.cloakedcent.app.ui.feature.settings.CategoriesRoute
import com.cloakedcent.app.ui.feature.settings.SettingsRoute
import com.cloakedcent.app.ui.feature.budget.ARG_BUDGET_ID
import com.cloakedcent.app.ui.feature.budget.BudgetDetailRoute
import com.cloakedcent.app.ui.feature.budget.BudgetEditorRoute
import com.cloakedcent.app.ui.feature.budget.BudgetsRoute
import android.net.Uri
import com.cloakedcent.app.domain.model.AnalysisPeriod
import com.cloakedcent.app.ui.feature.analysis.ARG_ANALYSIS_ID
import com.cloakedcent.app.ui.feature.analysis.ARG_ANALYSIS_KEY
import com.cloakedcent.app.ui.feature.analysis.ARG_ANALYSIS_PERIOD
import com.cloakedcent.app.ui.feature.analysis.AnalysisNavigation
import com.cloakedcent.app.ui.feature.analysis.AnalysisRoute
import com.cloakedcent.app.ui.feature.analysis.BrandAnalysisRoute
import com.cloakedcent.app.ui.feature.analysis.CategoryAnalysisRoute
import com.cloakedcent.app.ui.feature.analysis.LocationAnalysisRoute
import com.cloakedcent.app.ui.feature.analysis.ProductAnalysisRoute
import com.cloakedcent.app.ui.feature.receipt.ARG_PRODUCT_ID
import com.cloakedcent.app.ui.feature.receipt.ARG_RECEIPT_ID
import com.cloakedcent.app.ui.feature.receipt.ProductRoute
import com.cloakedcent.app.ui.feature.receipt.ProductsRoute
import com.cloakedcent.app.ui.feature.receipt.ReceiptCaptureRoute
import com.cloakedcent.app.ui.feature.receipt.ReceiptReviewRoute
import com.cloakedcent.app.ui.feature.receipt.ReceiptsRoute
import com.cloakedcent.app.ui.feature.recurring.ARG_SCHEDULE_ID
import com.cloakedcent.app.ui.feature.recurring.RecurringDetailRoute
import com.cloakedcent.app.ui.feature.recurring.RecurringEditorRoute
import com.cloakedcent.app.ui.feature.recurring.RecurringListRoute
import com.cloakedcent.app.ui.feature.recurring.UpcomingRoute
import com.cloakedcent.app.ui.feature.transaction.TransactionDetailRoute
import com.cloakedcent.app.ui.feature.transaction.TransactionEditorRoute
import com.cloakedcent.app.ui.feature.transaction.TransactionEditorViewModel

internal object Routes {
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
    const val BACKUP = "backup"
    const val RESTORE = "restore"
    const val LICENCES = "licences"
    const val CATEGORIES = "categories"
    const val BUDGETS = "budgets"
    const val BUDGET = "budget/{$ARG_BUDGET_ID}"
    const val BUDGET_EDIT = "budget-edit?$ARG_BUDGET_ID={$ARG_BUDGET_ID}"
    const val RECURRING = "recurring"
    const val SCHEDULE = "recurring/{$ARG_SCHEDULE_ID}"
    const val SCHEDULE_EDIT = "recurring-edit?$ARG_SCHEDULE_ID={$ARG_SCHEDULE_ID}"
    const val UPCOMING = "upcoming"
    const val RECEIPTS = "receipts"
    const val RECEIPT_CAPTURE = "receipt-capture"
    const val RECEIPT = "receipt/{$ARG_RECEIPT_ID}"
    const val PRODUCTS = "products"
    const val PRODUCT = "product/{$ARG_PRODUCT_ID}"

    const val ANALYSIS = "analysis"
    private const val P = "$ARG_ANALYSIS_PERIOD={$ARG_ANALYSIS_PERIOD}"
    const val ANALYSIS_CATEGORY = "analysis/category/{$ARG_ANALYSIS_ID}?$P"
    const val ANALYSIS_BRAND = "analysis/store?$ARG_ANALYSIS_KEY={$ARG_ANALYSIS_KEY}&$P"
    const val ANALYSIS_LOCATION = "analysis/location/{$ARG_ANALYSIS_ID}?$P"
    const val ANALYSIS_PRODUCT = "analysis/product/{$ARG_ANALYSIS_ID}?$P"

    fun analysisCategory(id: Long, period: AnalysisPeriod) = "analysis/category/$id?$ARG_ANALYSIS_PERIOD=${period.encode()}"
    fun analysisBrand(key: String, period: AnalysisPeriod) =
        "analysis/store?$ARG_ANALYSIS_KEY=${Uri.encode(key)}&$ARG_ANALYSIS_PERIOD=${period.encode()}"
    fun analysisLocation(id: Long, period: AnalysisPeriod) = "analysis/location/$id?$ARG_ANALYSIS_PERIOD=${period.encode()}"
    fun analysisProduct(id: Long, period: AnalysisPeriod) = "analysis/product/$id?$ARG_ANALYSIS_PERIOD=${period.encode()}"

    fun receipt(id: Long) = "receipt/$id"
    fun product(id: Long) = "product/$id"

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
fun CloakedCentNavHost() {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    val optionalId = { name: String -> navArgument(name) { type = NavType.LongType; defaultValue = -1L } }
    val requiredId = navArgument(TransactionEditorViewModel.ARG_TRANSACTION_ID) { type = NavType.LongType }
    val entry by nav.currentBackStackEntryAsState()
    val section = TopLevelDestination.of(entry?.destination)

    // The bar shows only on the five section screens; detail and editor screens get the full height.
    Scaffold(
        bottomBar = { if (section != null) CloakedCentNavigationBar(section, nav::navigateToTopLevel) },
        contentWindowInsets = WindowInsets(0),
    ) { barPadding ->
        NavHost(nav, startDestination = Routes.DASHBOARD, modifier = Modifier.padding(barPadding).consumeWindowInsets(barPadding)) {
            composable(Routes.DASHBOARD) {
                DashboardRoute(
                    onAddTransaction = { nav.navigate(Routes.newTransaction(it)) },
                    onAddAccount = { nav.navigate(Routes.account(null)) },
                    onOpenAccount = { nav.navigate(Routes.history(it)) },
                    onOpenTransaction = { nav.navigate(Routes.transaction(it)) },
                    onOpenHistory = { nav.navigate(Routes.history(null)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpenBudgets = { nav.navigateToTopLevel(TopLevelDestination.BUDGETS) },
                    onOpenUpcoming = { nav.navigate(Routes.UPCOMING) },
                )
            }
            val analysisNav = AnalysisNavigation(
                onBack = back,
                onCategory = { id, period -> nav.navigate(Routes.analysisCategory(id, period)) },
                onBrand = { key, period -> nav.navigate(Routes.analysisBrand(key, period)) },
                onLocation = { id, period -> nav.navigate(Routes.analysisLocation(id, period)) },
                onProduct = { id, period -> nav.navigate(Routes.analysisProduct(id, period)) },
                onEditProduct = { nav.navigate(Routes.product(it)) },
            )
            val periodArg = navArgument(ARG_ANALYSIS_PERIOD) { type = NavType.StringType; nullable = true; defaultValue = null }
            val idArg = navArgument(ARG_ANALYSIS_ID) { type = NavType.LongType }
            composable(Routes.ANALYSIS) { AnalysisRoute(analysisNav) }
            composable(Routes.ANALYSIS_CATEGORY, listOf(idArg, periodArg)) { CategoryAnalysisRoute(analysisNav) }
            composable(
                Routes.ANALYSIS_BRAND,
                listOf(navArgument(ARG_ANALYSIS_KEY) { type = NavType.StringType; defaultValue = "" }, periodArg),
            ) { BrandAnalysisRoute(analysisNav) }
            composable(Routes.ANALYSIS_LOCATION, listOf(idArg, periodArg)) { LocationAnalysisRoute(analysisNav) }
            composable(Routes.ANALYSIS_PRODUCT, listOf(idArg, periodArg)) { ProductAnalysisRoute(analysisNav) }
            composable(Routes.RECEIPTS) {
                ReceiptsRoute(
                    onBack = null,
                    onScan = { nav.navigate(Routes.RECEIPT_CAPTURE) },
                    onOpen = { nav.navigate(Routes.receipt(it)) },
                    onProducts = { nav.navigate(Routes.PRODUCTS) },
                )
            }
            composable(Routes.RECEIPT_CAPTURE) {
                ReceiptCaptureRoute(
                    onBack = back,
                    // The capture screen is done once its pages are stored; Back from review returns to the list.
                    onCreated = { id -> nav.navigate(Routes.receipt(id)) { popUpTo(Routes.RECEIPT_CAPTURE) { inclusive = true } } },
                    onOpenExisting = { id -> nav.navigate(Routes.receipt(id)) { popUpTo(Routes.RECEIPT_CAPTURE) { inclusive = true } } },
                )
            }
            composable(Routes.RECEIPT, listOf(navArgument(ARG_RECEIPT_ID) { type = NavType.LongType })) {
                ReceiptReviewRoute(
                    onBack = back,
                    onOpenReceipt = { nav.navigate(Routes.receipt(it)) },
                    onOpenTransaction = { nav.navigate(Routes.transaction(it)) },
                )
            }
            composable(Routes.PRODUCTS) { ProductsRoute(onBack = back, onOpen = { nav.navigate(Routes.product(it)) }) }
            composable(Routes.PRODUCT, listOf(navArgument(ARG_PRODUCT_ID) { type = NavType.LongType })) { ProductRoute(onBack = back) }
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
            composable(Routes.SETTINGS) {
                SettingsRoute(
                    onBack = back,
                    onBackup = { nav.navigate(Routes.BACKUP) },
                    onRestore = { nav.navigate(Routes.RESTORE) },
                    onLicences = { nav.navigate(Routes.LICENCES) },
                    onCategories = { nav.navigate(Routes.CATEGORIES) },
                )
            }
            composable(Routes.BACKUP) { BackupRoute(restore = false, onBack = back) }
            composable(Routes.RESTORE) { BackupRoute(restore = true, onBack = back) }
            composable(Routes.LICENCES) { AcknowledgementsRoute(onBack = back) }
            composable(Routes.CATEGORIES) { CategoriesRoute(onBack = back) }
            composable(Routes.BUDGETS) {
                BudgetsRoute(onBack = null, onOpen = { nav.navigate(Routes.budget(it)) }, onAdd = { nav.navigate(Routes.budgetEdit(null)) })
            }
            composable(Routes.BUDGET, listOf(navArgument(ARG_BUDGET_ID) { type = NavType.LongType })) {
                BudgetDetailRoute(onBack = back, onEdit = { nav.navigate(Routes.budgetEdit(it)) })
            }
            composable(Routes.BUDGET_EDIT, listOf(optionalId(ARG_BUDGET_ID))) { BudgetEditorRoute(onDone = back) }
            composable(Routes.RECURRING) {
                RecurringListRoute(
                    onBack = null,
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
                TransactionDetailRoute(
                    onBack = back,
                    onEdit = { nav.navigate(Routes.editTransaction(it)) },
                    onOpenReceipt = { nav.navigate(Routes.receipt(it)) },
                    onOpenTransaction = { nav.navigate(Routes.transaction(it)) },
                )
            }
            composable(Routes.EDIT_TRANSACTION, listOf(requiredId)) { TransactionEditorRoute(onDone = back) }
        }
    }
}
