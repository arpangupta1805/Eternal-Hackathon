package com.eternallite.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.eternallite.app.data.Address
import com.eternallite.app.net.HttpException
import com.eternallite.app.net.NetworkException
import kotlinx.coroutines.launch

private val ROLES = listOf("CUSTOMER" to "Customer", "RIDER" to "Rider", "STORE" to "Store node")

/** First-time setup. Needs internet once. */
@Composable
fun OnboardingScreen() {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()

    var serverUrl by rememberSaveable { mutableStateOf("http://192.168.1.10:8080") }
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var line1 by rememberSaveable { mutableStateOf("") }
    var line2 by rememberSaveable { mutableStateOf("") }
    var landmark by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf("CUSTOMER") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val complete = serverUrl.isNotBlank() && name.isNotBlank() && phone.isNotBlank() && line1.isNotBlank()

    Screen(title = "Set up Eternal Lite", showStatus = false) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "This one-time setup needs internet. After it, you can order with no signal.",
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                phone, { phone = it.filter(Char::isDigit).take(10) },
                label = { Text("Phone number") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(line1, { line1 = it }, label = { Text("Address: flat, room or building") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(line2, { line2 = it }, label = { Text("Address: area (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(landmark, { landmark = it }, label = { Text("Landmark (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("This phone is a", style = MaterialTheme.typography.titleMedium)
            Column {
                for ((value, label) in ROLES) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = role == value, role = Role.RadioButton) { role = value }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = role == value, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }

            OutlinedTextField(
                serverUrl, { serverUrl = it },
                label = { Text("Server address") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }

            Button(
                enabled = complete && !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            container.enroll(serverUrl, name, phone, Address(line1.trim(), line2.trim(), landmark.trim()), role)
                        } catch (e: NetworkException) {
                            error = "Could not reach the server. First-time setup needs internet. Check your connection and the server address, then try again."
                        } catch (e: HttpException) {
                            error = "The server did not accept the setup (error ${e.code}). Check the server address."
                        } catch (e: Exception) {
                            error = "Setup failed: ${e.message ?: "unknown error"}."
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "Setting up…" else "Set up this phone", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
