package com.pocketbudget.app.ui.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketbudget.app.PocketBudgetApp
import com.pocketbudget.app.R
import com.pocketbudget.app.domain.model.Category
import com.pocketbudget.app.domain.model.CategoryKind
import com.pocketbudget.app.domain.model.CategoryUsage
import com.pocketbudget.app.domain.model.MAX_CATEGORY_DEPTH
import com.pocketbudget.app.domain.model.pathLabels
import com.pocketbudget.app.domain.model.withDescendants
import com.pocketbudget.app.domain.repository.CategoryRepository
import com.pocketbudget.app.ui.components.BackTopBar
import com.pocketbudget.app.ui.components.ConfirmDialog
import com.pocketbudget.app.ui.components.PickerField
import com.pocketbudget.app.ui.components.SectionHeader
import com.pocketbudget.app.ui.components.ledgerErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A category placed in its tree: [depth] is 1 for a top-level category. */
data class CategoryNode(val category: Category, val depth: Int)

/** Adding a category of [kind], under [parent] or at the top level. */
data class AddRequest(val kind: CategoryKind, val parent: Category?)

/** Deleting [category] (with its sub-categories), which [usage] describes. */
data class DeleteRequest(val category: Category, val usage: CategoryUsage, val targets: List<Pair<Long, String>>)

data class CategoriesUiState(
    val categories: List<Category>? = null,
    val adding: AddRequest? = null,
    val deleting: DeleteRequest? = null,
    @StringRes val error: Int? = null,
    val busy: Boolean = false,
)

/** Categories of [kind] in tree order: each followed by its sub-categories, siblings by name. */
fun List<Category>.tree(kind: CategoryKind): List<CategoryNode> {
    val children = filter { it.kind == kind }.groupBy { it.parentId }
    val ids = filter { it.kind == kind }.map { it.id }.toSet()
    fun walk(parentId: Long?, depth: Int): List<CategoryNode> =
        children[parentId].orEmpty().sortedBy { it.name.lowercase() }
            .flatMap { listOf(CategoryNode(it, depth)) + walk(it.id, depth + 1) }
    // A category whose parent is missing is shown at the top level rather than lost.
    val orphans = filter { it.kind == kind && it.parentId != null && it.parentId !in ids }
    return walk(null, 1) + orphans.flatMap { listOf(CategoryNode(it, 1)) + walk(it.id, 2) }
}

class CategoriesViewModel(private val repository: CategoryRepository) : ViewModel() {

    private val _state = MutableStateFlow(CategoriesUiState())
    val state: StateFlow<CategoriesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { all -> _state.update { it.copy(categories = all) } }
        }
    }

    fun startAdding(kind: CategoryKind, parent: Category?) = _state.update { it.copy(adding = AddRequest(kind, parent), error = null) }

    fun add(name: String) {
        val request = _state.value.adding ?: return
        act { repository.create(name, request.kind, request.parent?.id); _state.update { it.copy(adding = null) } }
    }

    /** Looks up what uses the category, then asks the user how to proceed. */
    fun startDeleting(category: Category) = act {
        val usage = repository.usage(category.id)
        val all = _state.value.categories.orEmpty()
        val excluded = all.withDescendants(category.id)
        val labels = all.pathLabels()
        val targets = all.filter { it.kind == category.kind && it.id !in excluded }
            .map { it.id to labels.getValue(it.id) }.sortedBy { it.second.lowercase() }
        _state.update { it.copy(deleting = DeleteRequest(category, usage, targets)) }
    }

    /** [moveTo] is required while anything uses the category; the repository refuses otherwise. */
    fun delete(moveTo: Long?) {
        val request = _state.value.deleting ?: return
        act { repository.delete(request.category.id, moveTo); _state.update { it.copy(deleting = null) } }
    }

    /** Declining leaves everything as it was: nothing is moved and nothing is deleted. */
    fun dismiss() = _state.update { it.copy(adding = null, deleting = null, error = null) }

    private fun act(action: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = ledgerErrorMessage(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { CategoriesViewModel((this[APPLICATION_KEY] as PocketBudgetApp).container.categoryRepository) }
        }
    }
}

@Composable
fun CategoriesRoute(onBack: () -> Unit, viewModel: CategoriesViewModel = viewModel(factory = CategoriesViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CategoriesScreen(
        state = state,
        onBack = onBack,
        onAdd = viewModel::startAdding,
        onDelete = viewModel::startDeleting,
        onConfirmAdd = viewModel::add,
        onConfirmDelete = viewModel::delete,
        onDismiss = viewModel::dismiss,
    )
}

@Composable
fun CategoriesScreen(
    state: CategoriesUiState,
    onBack: () -> Unit,
    onAdd: (CategoryKind, Category?) -> Unit,
    onDelete: (Category) -> Unit,
    onConfirmAdd: (String) -> Unit,
    onConfirmDelete: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val all = state.categories.orEmpty()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.categories_title), onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item { Text(stringResource(R.string.categories_intro, MAX_CATEGORY_DEPTH), style = MaterialTheme.typography.bodyMedium) }
            // An error with no dialog open (e.g. looking up usage failed) is shown here.
            if (state.adding == null && state.deleting == null) {
                state.error?.let { item { Text(stringResource(it), color = MaterialTheme.colorScheme.error) } }
            }
            listOf(CategoryKind.EXPENSE to R.string.categories_expense, CategoryKind.INCOME to R.string.categories_income)
                .forEach { (kind, title) ->
                    item(key = "header-$kind") {
                        SectionHeader(stringResource(title), actionLabel = stringResource(R.string.action_add), onAction = { onAdd(kind, null) })
                    }
                    val nodes = all.tree(kind)
                    if (state.categories != null && nodes.isEmpty()) {
                        item(key = "empty-$kind") {
                            Text(
                                stringResource(R.string.categories_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                    items(nodes, key = { it.category.id }) { node -> CategoryRow(node, onAdd = { onAdd(kind, node.category) }, onDelete = { onDelete(node.category) }) }
                }
        }
    }

    state.adding?.let { request -> AddCategoryDialog(request, state.error, state.busy, onConfirmAdd, onDismiss) }
    state.deleting?.let { request -> DeleteCategoryDialog(request, state.error, state.busy, onConfirmDelete, onDismiss) }
}

@Composable
private fun CategoryRow(node: CategoryNode, onAdd: () -> Unit, onDelete: () -> Unit) {
    val name = node.category.name
    Row(
        Modifier.fillMaxWidth().padding(start = ((node.depth - 1) * 24).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = if (node.depth == 1) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        // Sub-categories stop at the nesting limit.
        if (node.depth < MAX_CATEGORY_DEPTH) {
            IconButton(onClick = onAdd) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.categories_add_sub_to, name))
            }
        }
        IconButton(onClick = onDelete) {
            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.categories_delete_named, name), tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun AddCategoryDialog(request: AddRequest, @StringRes error: Int?, busy: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                request.parent?.let { stringResource(R.string.categories_new_sub_title, it.name) }
                    ?: stringResource(if (request.kind == CategoryKind.EXPENSE) R.string.categories_new_expense_title else R.string.categories_new_income_title),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.categories_name)) },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank() && !busy) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun DeleteCategoryDialog(request: DeleteRequest, @StringRes error: Int?, busy: Boolean, onConfirm: (Long?) -> Unit, onDismiss: () -> Unit) {
    val usage = request.usage
    val title = stringResource(R.string.categories_delete_title, request.category.name)
    val erased = if (usage.subcategories > 0) {
        pluralStringResource(R.plurals.categories_erased_with_subs, usage.subcategories, usage.subcategories)
    } else {
        stringResource(R.string.categories_erased)
    }
    if (!usage.inUse) {
        ConfirmDialog(title, erased, stringResource(R.string.action_delete), onConfirm = { onConfirm(null) }, onDismiss = onDismiss)
        return
    }
    var target by remember { mutableStateOf<Long?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        if (usage.subcategories > 0) R.string.categories_in_use_with_subs_body else R.string.categories_in_use_body,
                        usageSummary(usage),
                    ),
                )
                if (request.targets.isEmpty()) {
                    Text(stringResource(R.string.categories_no_target), color = MaterialTheme.colorScheme.error)
                } else {
                    PickerField(stringResource(R.string.categories_move_to), request.targets, target, { target = it })
                }
                Text(erased)
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (request.targets.isNotEmpty()) {
                val enabled = target != null && !busy
                TextButton(onClick = { onConfirm(target) }, enabled = enabled) {
                    // Red only once it can act; disabled keeps the button's own dimmed colour.
                    Text(
                        stringResource(R.string.categories_move_and_delete),
                        color = if (enabled) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** "3 transactions, 1 budget and 2 recurring items". */
@Composable
private fun usageSummary(usage: CategoryUsage): String {
    val parts = listOfNotNull(
        usage.transactions.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.categories_usage_transactions, it, it) },
        usage.budgets.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.categories_usage_budgets, it, it) },
        usage.recurring.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.categories_usage_recurring, it, it) },
    )
    return if (parts.size < 2) {
        parts.joinToString()
    } else {
        stringResource(R.string.categories_usage_join, parts.dropLast(1).joinToString(", "), parts.last())
    }
}
