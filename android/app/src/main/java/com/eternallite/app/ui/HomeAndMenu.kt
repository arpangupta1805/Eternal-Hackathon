package com.eternallite.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eternallite.app.data.ItemEntity
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onStore: (String) -> Unit, onCart: () -> Unit, onOrders: () -> Unit) {
    val container = LocalContainer.current
    val stores by container.catalog.stores.collectAsState(initial = emptyList())
    val cart by container.cart.rows.collectAsState(initial = emptyList())
    val cartCount = cart.sumOf { it.qty }

    Screen(
        title = "Order",
        actionLabel = "My orders",
        onAction = onOrders,
        bottomBar = if (cartCount > 0) {
            { Button(onClick = onCart, modifier = Modifier.fillMaxWidth()) { Text("View cart ($cartCount)", style = MaterialTheme.typography.titleMedium) } }
        } else null,
    ) {
        if (stores.isEmpty()) {
            Text(
                "No menu saved on this phone yet. It downloads by itself when this phone is online.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(stores, key = { it.storeId }) { store ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onStore(store.storeId) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(store.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                if (store.kind == "RESTAURANT") "Restaurant" else "Groceries",
                                style = MaterialTheme.typography.bodyMedium, color = Muted,
                            )
                            if (store.open) {
                                Text("${store.etaMinutes} min, delivery ${rupees(store.deliveryFee)}", style = MaterialTheme.typography.bodyLarge)
                            } else {
                                Text("Closed right now", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MenuScreen(storeId: String, onBack: () -> Unit, onCart: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val store by container.catalog.store(storeId).collectAsState(initial = null)
    val items by container.catalog.items(storeId).collectAsState(initial = emptyList())
    val cart by container.cart.rows.collectAsState(initial = emptyList())

    val qtyById = cart.filter { it.storeId == storeId }.associate { it.itemId to it.qty }
    val otherStoreCart = cart.isNotEmpty() && cart.first().storeId != storeId
    val cartCount = qtyById.values.sum()
    val cartTotal = cart.filter { it.storeId == storeId }.sumOf { it.price * it.qty }
    var pendingAdd by remember { mutableStateOf<ItemEntity?>(null) }

    fun add(item: ItemEntity) {
        if (otherStoreCart) pendingAdd = item else scope.launch { container.cart.change(item, +1) }
    }

    pendingAdd?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingAdd = null },
            title = { Text("Start a new cart?") },
            text = { Text("Your cart has items from another store. Adding this item empties that cart.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingAdd = null
                    scope.launch { container.cart.change(item, +1) }
                }) { Text("Empty cart and add") }
            },
            dismissButton = { TextButton(onClick = { pendingAdd = null }) { Text("Keep my cart") } },
        )
    }

    Screen(
        title = store?.name ?: "Menu",
        onBack = onBack,
        bottomBar = if (cartCount > 0) {
            {
                Button(onClick = onCart, modifier = Modifier.fillMaxWidth()) {
                    Text("View cart: $cartCount ${if (cartCount == 1) "item" else "items"}, ${rupees(cartTotal)}", style = MaterialTheme.typography.titleMedium)
                }
            }
        } else null,
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (store?.open == false) {
                item {
                    Text(
                        "This store is closed right now. Orders will be rejected until it opens.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            for ((category, group) in items.groupBy { it.category }) {
                item(key = "cat:$category") {
                    Text(
                        category, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Muted,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(group, key = { it.itemId }) { item ->
                    MenuRow(
                        item = item,
                        qty = qtyById[item.itemId] ?: 0,
                        onAdd = { add(item) },
                        onRemove = { scope.launch { container.cart.change(item, -1) } },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun MenuRow(item: ItemEntity, qty: Int, onAdd: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (item.inStock) MaterialTheme.colorScheme.onSurface else Muted.copy(alpha = 0.6f),
            )
            Text(
                rupees(item.price),
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.inStock) MaterialTheme.colorScheme.onSurface else Muted.copy(alpha = 0.6f),
            )
        }
        when {
            !item.inStock -> Text(
                "Out of stock",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
            qty == 0 -> OutlinedButton(onClick = onAdd) { Text("Add") }
            else -> QtyStepper(qty, onAdd, onRemove)
        }
    }
}

@Composable
fun QtyStepper(qty: Int, onAdd: () -> Unit, onRemove: () -> Unit, canAdd: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onRemove, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(44.dp)) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            qty.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.width(40.dp),
        )
        OutlinedButton(onClick = onAdd, enabled = canAdd, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(44.dp)) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}
