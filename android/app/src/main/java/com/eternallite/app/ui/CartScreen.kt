package com.eternallite.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.eternallite.app.data.CartRow
import com.eternallite.app.data.ItemEntity
import com.eternallite.app.data.OnUnavailable
import com.eternallite.app.mesh.Protocol
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun CartScreen(onBack: () -> Unit, onPlaced: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val rows by container.cart.rows.collectAsState(initial = emptyList())
    val online by container.connectivity.isOnline.collectAsState()
    val storeId = rows.firstOrNull()?.storeId
    val store by remember(storeId) { storeId?.let { container.catalog.store(it) } ?: flowOf(null) }.collectAsState(initial = null)
    val storeItems by remember(storeId) { storeId?.let { container.catalog.items(it) } ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())

    var toleranceText by rememberSaveable { mutableStateOf((Protocol.DEFAULT_PRICE_TOLERANCE / 100).toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickingFor by remember { mutableStateOf<CartRow?>(null) }

    val itemsTotal = rows.sumOf { it.price * it.qty }
    val fee = store?.deliveryFee ?: 0
    val total = itemsTotal + fee
    val toleranceRupees = toleranceText.toIntOrNull()
    val itemById = storeItems.associateBy { it.itemId }

    pickingFor?.let { row ->
        SubstitutePicker(
            forName = row.name,
            choices = storeItems.filter { it.inStock && it.itemId != row.itemId },
            onPick = { choice ->
                pickingFor = null
                scope.launch { container.cart.setRule(row.itemId, OnUnavailable.SUBSTITUTE, choice.itemId) }
            },
            onDismiss = { pickingFor = null },
        )
    }

    Screen(
        title = "Cart",
        onBack = onBack,
        bottomBar = if (rows.isNotEmpty()) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
                    Button(
                        enabled = !busy && toleranceRupees != null,
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    val orderId = container.orders.placeOrder((toleranceRupees ?: 0) * 100)
                                    container.uploader.kick() // online: sent at once. Offline: waits in the queue.
                                    onPlaced(orderId)
                                } catch (e: Exception) {
                                    error = e.message ?: "Could not place the order."
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${if (online) "Place order" else "Place order via relay"}: ${rupees(total)}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        } else null,
    ) {
        if (rows.isEmpty()) {
            Text("Your cart is empty. Go back and add items from a store.", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            return@Screen
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            store?.let { Text(it.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }

            for (row in rows) {
                CartItemCard(
                    row = row,
                    substituteName = row.substituteItemId?.let { itemById[it]?.name },
                    onAdd = { itemById[row.itemId]?.let { scope.launch { container.cart.change(it, +1) } } },
                    onRemove = { itemById[row.itemId]?.let { scope.launch { container.cart.change(it, -1) } } },
                    onRule = { rule ->
                        if (rule == OnUnavailable.SUBSTITUTE) pickingFor = row
                        else scope.launch { container.cart.setRule(row.itemId, rule, null) }
                    },
                )
            }

            HorizontalDivider()
            TotalRow("Items", rupees(itemsTotal))
            TotalRow("Delivery", rupees(fee))
            TotalRow("Total", rupees(total), bold = true)

            HorizontalDivider()
            Text("If prices have changed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Accept if the total rises by up to ₹", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    toleranceText, { toleranceText = it.filter(Char::isDigit).take(4) },
                    singleLine = true,
                    isError = toleranceRupees == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
            }
            toleranceRupees?.let {
                Text("You will never pay more than ${rupees(total + it * 100)}.", style = MaterialTheme.typography.bodyMedium, color = Muted)
            }

            HorizontalDivider()
            TotalRow("Payment", "Cash on delivery")
            Text("This order is valid for 20 minutes.", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun TotalRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val style = if (bold) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
        val weight = if (bold) FontWeight.Bold else FontWeight.Normal
        Text(label, style = style, fontWeight = weight)
        Text(value, style = style, fontWeight = weight)
    }
}

@Composable
private fun CartItemCard(
    row: CartRow,
    substituteName: String?,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onRule: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${rupees(row.price)} each, ${rupees(row.price * row.qty)}", style = MaterialTheme.typography.bodyLarge)
                }
                QtyStepper(row.qty, onAdd, onRemove, canAdd = row.inStock)
            }
            if (!row.inStock) {
                Text(
                    "Now out of stock. Your choice below decides what happens.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                )
            }
            Text("If unavailable", style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.padding(top = 4.dp))
            RuleOption("Remove it", row.onUnavailable == OnUnavailable.DROP) { onRule(OnUnavailable.DROP) }
            RuleOption(
                if (row.onUnavailable == OnUnavailable.SUBSTITUTE && substituteName != null) "Replace with $substituteName (tap to change)" else "Replace with…",
                row.onUnavailable == OnUnavailable.SUBSTITUTE,
            ) { onRule(OnUnavailable.SUBSTITUTE) }
            RuleOption("Cancel whole order", row.onUnavailable == OnUnavailable.CANCEL_ORDER) { onRule(OnUnavailable.CANCEL_ORDER) }
        }
    }
}

@Composable
private fun RuleOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun SubstitutePicker(forName: String, choices: List<ItemEntity>, onPick: (ItemEntity) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replace $forName with") },
        text = {
            if (choices.isEmpty()) {
                Text("Nothing else from this store is in stock.")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (choice in choices) {
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(choice) }.padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(choice.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(rupees(choice.price), style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
