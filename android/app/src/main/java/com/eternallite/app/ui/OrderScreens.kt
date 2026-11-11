package com.eternallite.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eternallite.app.data.AckResult
import com.eternallite.app.data.OrderBody
import com.eternallite.app.data.OrderChange
import com.eternallite.app.data.OrderEntity
import com.eternallite.app.data.OrderState
import com.eternallite.app.mesh.Protocol

fun stateLabel(state: String): String = when (state) {
    OrderState.QUEUED -> "Queued"
    OrderState.RELAYED -> "Handed to a relay"
    OrderState.CONFIRMED -> "Confirmed"
    OrderState.CONFIRMED_MODIFIED -> "Confirmed with changes"
    OrderState.REJECTED -> "Rejected"
    OrderState.UNCONFIRMED -> "Not confirmed yet"
    else -> state
}

fun stateColor(state: String): Color = when (state) {
    OrderState.CONFIRMED -> OkGreen
    OrderState.CONFIRMED_MODIFIED -> WarnAmber
    OrderState.REJECTED -> BrandRed
    OrderState.UNCONFIRMED -> WarnAmber
    else -> Muted
}

fun rejectReasonText(reason: String?): String = when (reason) {
    "EXPIRED" -> "The order reached the server after its 20 minutes ran out. Nothing was placed."
    "STORE_CLOSED" -> "The store is closed. Nothing was placed."
    "ITEM_UNAVAILABLE" -> "An item you needed is out of stock, so the order was not placed."
    "PRICE_EXCEEDED" -> "Prices went up past the limit you set, so the order was not placed."
    else -> "The server did not accept this order."
}

private fun parseResult(order: OrderEntity): AckResult? = order.ackResultJson?.let {
    runCatching { Protocol.json.decodeFromString(AckResult.serializer(), it) }.getOrNull()
}

private fun parseBody(order: OrderEntity): OrderBody? =
    runCatching { Protocol.json.decodeFromString(OrderBody.serializer(), order.bodyJson) }.getOrNull()

@Composable
fun OrdersScreen(onBack: () -> Unit, onOrder: (String) -> Unit) {
    val container = LocalContainer.current
    val orders by container.orders.orders.collectAsState(initial = emptyList())
    val now = tickingNow(15_000)

    Screen(title = "My orders", onBack = onBack) {
        if (orders.isEmpty()) {
            Text("No orders yet. Orders you place appear here.", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(orders, key = { it.orderId }) { order ->
                    val result = remember(order.ackResultJson) { parseResult(order) }
                    val body = remember(order.bodyJson) { parseBody(order) }
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOrder(order.orderId) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stateLabel(order.state), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = stateColor(order.state))
                            Text(order.storeName, style = MaterialTheme.typography.titleMedium)
                            val total = if (result != null && result.status != OrderState.REJECTED) result.finalTotal else body?.totalSeen
                            Text(
                                listOfNotNull(total?.let(::rupees), result?.serverOrderRef, "placed ${agoText(order.createdAt, now)}").joinToString(", "),
                                style = MaterialTheme.typography.bodyLarge, color = Muted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OrderStatusScreen(orderId: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val order by container.orders.order(orderId).collectAsState(initial = null)
    val now = tickingNow(1_000)

    Screen(title = "Order status", onBack = onBack) {
        val o = order
        if (o == null) {
            Text("Loading this order…", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            return@Screen
        }
        val result = remember(o.ackResultJson) { parseResult(o) }
        val body = remember(o.bodyJson) { parseBody(o) }
        val storeItems by container.catalog.items(o.storeId).collectAsState(initial = emptyList())
        val nameOf: (String) -> String = { id -> storeItems.firstOrNull { it.itemId == id }?.name ?: id }
        val handedTo = o.handedTo.split(",").filter { it.isNotBlank() }.size
        val answered = result != null

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(o.storeName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            // Timeline
            Step(done = true, title = "Sealed and queued", detail = "Signed by this phone and encrypted for the server.")
            Step(
                done = handedTo > 0 || answered,
                title = when {
                    handedTo == 1 -> "Handed to 1 relay"
                    handedTo > 1 -> "Handed to $handedTo relays"
                    answered -> "Sent"
                    else -> "Waiting for a relay or signal"
                },
                detail = if (handedTo == 0 && !answered) "It goes out by itself as soon as a relay is near or this phone gets internet." else null,
            )
            Step(
                done = answered,
                title = when (o.state) {
                    OrderState.CONFIRMED, OrderState.CONFIRMED_MODIFIED, OrderState.REJECTED -> stateLabel(o.state)
                    OrderState.UNCONFIRMED -> "Not confirmed yet"
                    else -> "Waiting for confirmation"
                },
                color = if (answered) stateColor(o.state) else Muted,
                detail = null,
            )

            when {
                o.uploadRejected && !answered -> Notice(
                    "The server did not recognise this order. This phone may need to be set up again.",
                    BrandRed,
                )
                o.state == OrderState.UNCONFIRMED -> Notice(
                    "We could not confirm this order. It will update when a confirmation arrives or you are back online.",
                    WarnAmber,
                )
                !answered -> {
                    val left = (o.expiresAt - now).coerceAtLeast(0) / 1000
                    Notice("Valid for ${left / 60} min ${(left % 60).toString().padStart(2, '0')} s more.", Muted)
                }
            }

            HorizontalDivider()

            if (result != null && result.status != OrderState.REJECTED) {
                result.serverOrderRef?.let { Line("Order ref", it) }
                result.etaMinutes?.let { Line("Arrives in about", "$it min") }
                Text("What you will get", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                for (item in result.finalItems) Line("${item.qty} × ${item.name}", rupees(item.unitPrice * item.qty))
                Line("Delivery", rupees(result.deliveryFee))
                Line("Total, cash on delivery", rupees(result.finalTotal), bold = true)
                if (result.changes.isNotEmpty()) {
                    Text("Changes made by your rules", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    for (change in result.changes) {
                        Text(changeText(change, nameOf), style = MaterialTheme.typography.bodyLarge, color = WarnAmber, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (result != null) {
                Text(rejectReasonText(result.reason), style = MaterialTheme.typography.titleMedium, color = BrandRed, fontWeight = FontWeight.Bold)
            }

            if (result == null || result.status == OrderState.REJECTED) {
                body?.let { b ->
                    Text("What you asked for", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    for (line in b.items) Line("${line.qty} × ${nameOf(line.itemId)}", rupees(line.unitPriceSeen * line.qty))
                    Line("Delivery", rupees(b.deliveryFeeSeen))
                    Line("Total", rupees(b.totalSeen), bold = true)
                    Text("You will not pay more than ${rupees(b.maxTotal)}.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                }
            }
        }
    }
}

private fun changeText(change: OrderChange, nameOf: (String) -> String): String = when (change.change) {
    "DROPPED" -> "${nameOf(change.itemId)} was removed (out of stock)."
    "SUBSTITUTED" -> "${nameOf(change.itemId)} was replaced with ${change.newItemId?.let(nameOf) ?: "another item"}."
    "PRICE_CHANGED" -> "${nameOf(change.itemId)} now costs ${change.newPrice?.let(::rupees) ?: "a different price"}" +
        (change.oldPrice?.let { " (was ${rupees(it)})." } ?: ".")
    else -> "${nameOf(change.itemId)}: ${change.change}"
}

@Composable
private fun Step(done: Boolean, title: String, detail: String?, color: Color = if (done) OkGreen else Muted) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 6.dp).size(16.dp).background(if (done) color else Color(0xFFD6DCE5), CircleShape))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (done) color else Muted)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Muted) }
        }
    }
}

@Composable
private fun Notice(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = color, fontWeight = FontWeight.Bold)
}

@Composable
private fun Line(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val weight = if (bold) FontWeight.Bold else FontWeight.Normal
        val style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge
        Text(label, style = style, fontWeight = weight, modifier = Modifier.weight(1f))
        Text(value, style = style, fontWeight = weight)
    }
}
