package com.cloakedcent.app.ui.feature.receipt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cloakedcent.app.CloakedCentApp
import com.cloakedcent.app.R
import com.cloakedcent.app.domain.model.CategoryKind
import com.cloakedcent.app.domain.model.MeasureUnit
import com.cloakedcent.app.domain.model.NormalizedPrice
import com.cloakedcent.app.domain.model.PriceObservation
import com.cloakedcent.app.domain.model.Product
import com.cloakedcent.app.domain.model.ProductSummary
import com.cloakedcent.app.domain.model.ReceiptStatus
import com.cloakedcent.app.domain.model.ReceiptSummary
import com.cloakedcent.app.domain.model.pathLabels
import com.cloakedcent.app.domain.repository.CategoryRepository
import com.cloakedcent.app.domain.repository.ProductRepository
import com.cloakedcent.app.domain.repository.ReceiptRepository
import com.cloakedcent.app.ui.components.AmountInput
import com.cloakedcent.app.ui.components.BackTopBar
import com.cloakedcent.app.ui.components.EmptyState
import com.cloakedcent.app.ui.components.MoneyText
import com.cloakedcent.app.ui.components.PickerField
import com.cloakedcent.app.ui.components.SectionHeader
import com.cloakedcent.app.ui.components.ledgerErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

const val ARG_PRODUCT_ID = "productId"

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

// ------------------------------------------------------------------ Receipt list

class ReceiptsViewModel(receipts: ReceiptRepository) : ViewModel() {
    val receipts: StateFlow<List<ReceiptSummary>?> = receipts.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        val Factory = viewModelFactory {
            initializer { ReceiptsViewModel((this[APPLICATION_KEY] as CloakedCentApp).container.receiptRepository) }
        }
    }
}

@Composable
fun ReceiptsRoute(
    onBack: (() -> Unit)?,
    onScan: () -> Unit,
    onOpen: (Long) -> Unit,
    onProducts: () -> Unit,
    viewModel: ReceiptsViewModel = viewModel(factory = ReceiptsViewModel.Factory),
) {
    val receipts by viewModel.receipts.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            BackTopBar(stringResource(R.string.receipts_title), onBack) {
                TextButton(onClick = onProducts) { Text(stringResource(R.string.products_title)) }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(painterResource(R.drawable.ic_camera), null) },
                text = { Text(stringResource(R.string.action_scan_receipt)) },
            )
        },
    ) { padding ->
        val list = receipts ?: return@Scaffold
        if (list.isEmpty()) {
            EmptyState(
                stringResource(R.string.receipts_empty_title),
                stringResource(R.string.receipts_empty_body),
                Modifier.padding(padding).padding(16.dp),
                actionLabel = stringResource(R.string.action_scan_receipt),
                onAction = onScan,
            )
            return@Scaffold
        }
        val (pending, confirmed) = list.partition { it.status != ReceiptStatus.CONFIRMED }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (pending.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.receipts_needs_review)) }
                items(pending, key = { it.id }) { ReceiptRow(it) { onOpen(it.id) } }
            }
            if (confirmed.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.receipts_confirmed)) }
                items(confirmed, key = { it.id }) { ReceiptRow(it) { onOpen(it.id) } }
            }
        }
    }
}

@Composable
private fun ReceiptRow(receipt: ReceiptSummary, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(receipt.storeName ?: stringResource(R.string.receipt_unknown_store), style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        receipt.purchasedOn?.let(dateFormat::format),
                        stringResource(
                            when (receipt.status) {
                                ReceiptStatus.DRAFT -> R.string.receipt_status_draft
                                ReceiptStatus.NEEDS_REVIEW -> R.string.receipt_status_review
                                else -> R.string.receipt_status_confirmed
                            },
                        ),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            receipt.total?.let { MoneyText(it) }
        }
    }
}

// ------------------------------------------------------------------ Products and prices

@Composable
fun normalizedLabel(price: NormalizedPrice): String =
    stringResource(R.string.product_unit_price, AmountInput.format(price.perUnit), price.standardUnit.symbol)

class ProductsViewModel(products: ProductRepository) : ViewModel() {
    val products: StateFlow<List<ProductSummary>?> = products.observeProducts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        val Factory = viewModelFactory {
            initializer { ProductsViewModel((this[APPLICATION_KEY] as CloakedCentApp).container.productRepository) }
        }
    }
}

@Composable
fun ProductsRoute(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    viewModel: ProductsViewModel = viewModel(factory = ProductsViewModel.Factory),
) {
    val products by viewModel.products.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.products_title), onBack) }) { padding ->
        val list = products ?: return@Scaffold
        if (list.isEmpty()) {
            EmptyState(
                stringResource(R.string.products_empty_title),
                stringResource(R.string.products_empty_body),
                Modifier.padding(padding).padding(16.dp),
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list, key = { it.product.id }) { summary ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(summary.product.id) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(summary.product.name, style = MaterialTheme.typography.titleMedium)
                        summary.product.brand?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        summary.lastPrice?.let { last ->
                            Text(
                                stringResource(R.string.product_last_price, AmountInput.format(last.paid), last.storeName) +
                                    (last.normalized?.let { " · " + normalizedLabel(it) } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

data class ProductUiState(
    val product: Product? = null,
    val name: String = "",
    val brand: String = "",
    val categoryId: Long? = null,
    val packQuantity: String = "",
    val packUnit: MeasureUnit? = null,
    val categories: List<Pair<Long, String>> = emptyList(),
    val message: Int? = null,
    val saved: Boolean = false,
)

class ProductViewModel(
    private val products: ProductRepository,
    categories: CategoryRepository,
    val productId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(ProductUiState())
    val state: StateFlow<ProductUiState> = _state.asStateFlow()

    val prices: StateFlow<List<PriceObservation>> = products.observePrices(productId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val product = products.get(productId) ?: return@launch
            _state.update {
                it.copy(
                    product = product,
                    name = product.name,
                    brand = product.brand.orEmpty(),
                    categoryId = product.defaultCategoryId,
                    packQuantity = product.packQuantityMilli?.let(QuantityText::format).orEmpty(),
                    packUnit = product.packUnit,
                )
            }
        }
        viewModelScope.launch {
            categories.observeAll().map { all ->
                val labels = all.pathLabels()
                all.filter { it.kind == CategoryKind.EXPENSE }.map { it.id to labels.getValue(it.id) }.sortedBy { it.second }
            }.collect { options -> _state.update { it.copy(categories = options) } }
        }
    }

    fun edit(change: (ProductUiState) -> ProductUiState) = _state.update { change(it).copy(message = null, saved = false) }

    fun save() {
        val s = _state.value
        val pack = s.packQuantity.trim().takeIf { it.isNotEmpty() }?.let { QuantityText.parse(it) ?: return showError(R.string.error_receipt_quantity) }
        viewModelScope.launch {
            try {
                products.update(productId, s.name, s.brand, s.categoryId, pack, if (pack == null) null else s.packUnit ?: MeasureUnit.EACH)
                _state.update { it.copy(saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(ledgerErrorMessage(e))
            }
        }
    }

    private fun showError(id: Int) = _state.update { it.copy(message = id) }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as CloakedCentApp).container
                ProductViewModel(
                    container.productRepository,
                    container.categoryRepository,
                    requireNotNull(createSavedStateHandle().get<Long>(ARG_PRODUCT_ID)),
                )
            }
        }
    }
}

@Composable
fun ProductRoute(onBack: () -> Unit, viewModel: ProductViewModel = viewModel(factory = ProductViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prices by viewModel.prices.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    Scaffold(topBar = { BackTopBar(state.product?.name.orEmpty(), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.product == null) return@Column
            OutlinedTextField(state.name, { v -> viewModel.edit { it.copy(name = v) } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_item_name)) }, singleLine = true)
            OutlinedTextField(state.brand, { v -> viewModel.edit { it.copy(brand = v) } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.product_brand)) }, singleLine = true)
            PickerField(stringResource(R.string.field_category), state.categories, state.categoryId, { id -> viewModel.edit { it.copy(categoryId = id) } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    state.packQuantity,
                    { v -> viewModel.edit { it.copy(packQuantity = v) } },
                    Modifier.weight(1f),
                    label = { Text(stringResource(R.string.product_pack_size)) },
                    singleLine = true,
                )
                PickerField(
                    stringResource(R.string.field_unit),
                    MeasureUnit.entries.map { it to it.symbol },
                    state.packUnit,
                    { u -> viewModel.edit { it.copy(packUnit = u) } },
                    Modifier.weight(1f),
                )
            }
            state.message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_save)) }

            SectionHeader(stringResource(R.string.product_prices))
            prices.forEach { price ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${dateFormat.format(price.observedOn)} · ${price.storeName}")
                        Text(
                            listOfNotNull(
                                "${QuantityText.format(price.quantityMilli)} ${price.unit.symbol}",
                                price.normalized?.let { normalizedLabel(it) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    MoneyText(price.paid)
                }
            }
        }
    }
}
