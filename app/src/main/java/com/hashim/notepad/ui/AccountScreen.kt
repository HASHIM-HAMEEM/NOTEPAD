package com.hashim.notepad.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hashim.notepad.sync.SyncManager
import com.hashim.notepad.sync.SyncState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Sign-in, end-to-end encryption passphrase and sync status. Everything else in the app works without it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val sync = remember { SyncManager.get(context) }
    val status by sync.status.collectAsState()
    val session by sync.auth.session.collectAsState()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> String?) {
        busy = true; error = null; info = null
        scope.launch {
            try { info = block() } catch (e: Exception) { error = e.message ?: e.toString() } finally { busy = false }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Sync & account") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(
            Modifier.padding(padding).padding(24.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            when (status.state) {
                                SyncState.DISABLED -> "Sync not configured in this build"
                                SyncState.SIGNED_OUT -> "Not signed in — notes stay on this device"
                                SyncState.NEEDS_KEY -> "Signed in as ${session?.email}. Set your encryption passphrase to start syncing."
                                SyncState.IDLE -> "Synced as ${session?.email}"
                                SyncState.SYNCING -> "Syncing…"
                                SyncState.ERROR -> "Sync error"
                                SyncState.OFFLINE -> "Offline — changes queued"
                            }, style = MaterialTheme.typography.titleMedium
                        )
                        if (status.lastSyncAt > 0) Text("Last sync: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(status.lastSyncAt))}", style = MaterialTheme.typography.bodySmall)
                        Text("Pending changes: ${status.pending}", style = MaterialTheme.typography.bodySmall)
                        status.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (status.state == SyncState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }

                when (status.state) {
                    SyncState.DISABLED -> Unit
                    SyncState.SIGNED_OUT -> {
                        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { run { sync.signIn(email, password); null } }, enabled = !busy && email.isNotBlank() && password.isNotBlank()) { Text("Sign in") }
                            OutlinedButton(onClick = { run { sync.signUp(email, password) } }, enabled = !busy && email.isNotBlank() && password.length >= 6) { Text("Create account") }
                        }
                    }
                    SyncState.NEEDS_KEY -> {
                        Text("The passphrase encrypts your notes before they leave the device. It is never sent to the server and cannot be recovered — use the same one on every device.",
                            style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Encryption passphrase (8+ characters)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { run { sync.setPassphrase(passphrase); passphrase = ""; "Encryption enabled" } }, enabled = !busy && passphrase.length >= 8) { Text("Unlock sync") }
                            TextButton(onClick = { run { sync.signOut(); null } }, enabled = !busy) { Text("Sign out") }
                        }
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { run { if (sync.syncNow()) null else "Will retry when online" } }, enabled = !busy && status.state != SyncState.SYNCING) { Text("Sync now") }
                        TextButton(onClick = { run { sync.signOut(); null } }, enabled = !busy) { Text("Sign out") }
                    }
                }

                if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.height(20.dp).widthIn(20.dp)); Spacer(Modifier.widthIn(8.dp)); Text("  Working…") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}
