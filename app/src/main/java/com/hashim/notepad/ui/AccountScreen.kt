package com.hashim.notepad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hashim.notepad.sync.HttpException
import com.hashim.notepad.sync.SyncManager
import com.hashim.notepad.sync.SyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Sign-in, end-to-end encryption passphrase and sync status. Everything else in the app works without it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(onBack: () -> Unit, showBack: Boolean = true) {
    val context = LocalContext.current
    val sync = remember { SyncManager.get(context) }
    val status by sync.status.collectAsState()
    val passphraseOnServer by sync.passphraseOnServer.collectAsState()
    val session by sync.auth.session.collectAsState()
    LaunchedEffect(status.state, session?.userId) {
        if (status.state == SyncState.NEEDS_KEY) sync.checkPassphraseOnServer()
    }
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(sync.auth.pendingConfirmationEmail.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var confirmationEmail by remember { mutableStateOf(sync.auth.pendingConfirmationEmail.orEmpty()) }
    var addingAccount by rememberSaveable { mutableStateOf(false) }
    val accountState = if (session == null && status.state != SyncState.DISABLED) SyncState.SIGNED_OUT else status.state

    LaunchedEffect(session?.userId) {
        if (session != null) {
            password = ""
            confirmationEmail = ""
            addingAccount = false
        }
    }

    fun run(block: suspend () -> String?) {
        if (busy) return
        busy = true; error = null; info = null
        scope.launch {
            try {
                info = block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendlyAuthError(e)
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        containerColor = NotePadTheme.tokens.bg,
        topBar = {
            if (showBack) Row(Modifier.statusBarsPadding().padding(start = 12.dp, top = 8.dp)) {
                PillIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
            } else Spacer(Modifier.statusBarsPadding().height(40.dp))
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).imePadding().fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sync", style = MaterialTheme.typography.headlineMedium)
                Text("No account is needed to use NotePad. Add an account only to sync your notes across devices.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = NotePadTheme.tokens.surface, border = BorderStroke(Spec.hairline, NotePadTheme.tokens.border)) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dot = when (accountState) {
                                SyncState.IDLE -> androidx.compose.ui.graphics.Color(0xFF2E9E5B)
                                SyncState.SYNCING -> MaterialTheme.colorScheme.primary
                                SyncState.ERROR -> MaterialTheme.colorScheme.error
                                SyncState.OFFLINE, SyncState.NEEDS_KEY -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.outline
                            }
                            Box(Modifier.size(10.dp).background(dot, CircleShape))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                when (accountState) {
                                    SyncState.DISABLED -> "Sync off"; SyncState.SIGNED_OUT -> "On this device only"; SyncState.NEEDS_KEY -> "Passphrase needed"
                                    SyncState.IDLE -> if (status.lastSyncAt > 0 && status.pending == 0) "Up to date" else "Ready to sync"
                                    SyncState.SYNCING -> "Syncing"; SyncState.ERROR -> "Sync error"; SyncState.OFFLINE -> "Offline"
                                }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val title = when (accountState) {
                            SyncState.DISABLED -> "Sync not configured in this build"
                            SyncState.SIGNED_OUT -> "Your notes stay on this device"
                            SyncState.NEEDS_KEY -> when (passphraseOnServer) {
                                true -> "Signed in as ${session?.email}. Enter the passphrase you chose on your other device."
                                false -> "Signed in as ${session?.email}. Choose an encryption passphrase to start syncing."
                                null -> "Signed in as ${session?.email}. Checking account…"
                            }
                            SyncState.IDLE -> "Connected as ${session?.email}"
                            SyncState.SYNCING -> "Syncing…"
                            SyncState.ERROR -> "Sync error"
                            SyncState.OFFLINE -> "Offline — changes queued"
                        }
                        if (accountState == SyncState.DISABLED) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.CloudOff,
                                    null,
                                    Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(16.dp))
                                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            }
                        } else {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                        }
                        if (status.lastSyncAt > 0) Text("Last sync: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(status.lastSyncAt))}", style = MaterialTheme.typography.bodySmall)
                        Text("Pending changes: ${status.pending}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        status.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (status.state == SyncState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }

                when (accountState) {
                    SyncState.DISABLED -> Text("This build has no sync server configured. You can keep creating, editing and exporting notes without an account.")
                    SyncState.SIGNED_OUT -> {
                        if (!addingAccount && confirmationEmail.isBlank()) {
                            Button(onClick = { addingAccount = true }, modifier = Modifier.fillMaxWidth()) { Text("Add sync account") }
                        } else {
                            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                            OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                enabled = !busy, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { run { sync.signIn(email, password); null } }, modifier = Modifier.fillMaxWidth(), enabled = !busy && email.isNotBlank() && password.isNotBlank()) { Text("Connect existing account") }
                                OutlinedButton(
                                    onClick = {
                                        run {
                                            val result = sync.signUp(email, password)
                                            if (result != null) confirmationEmail = sync.auth.pendingConfirmationEmail.orEmpty()
                                            result
                                        }
                                    },
                                    enabled = !busy && email.isNotBlank() && password.length >= 6,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Create account") }
                            }
                        }
                        if (confirmationEmail.isNotBlank()) {
                            Text("Check your email and tap the link — it opens NotePad", style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(
                                onClick = {
                                    val address = confirmationEmail
                                    run { sync.resendSignUpConfirmation(address); "Confirmation email sent" }
                                },
                                enabled = !busy,
                            ) { Text("Resend") }
                        }
                    }
                    SyncState.NEEDS_KEY -> {
                        Text(
                            if (passphraseOnServer == true) {
                                "This account's notes are encrypted with a passphrase you set on another device. It is not your account password and NotePad cannot recover it — if you have forgotten it, disconnect and reset sync from that device."
                            } else {
                                "The passphrase encrypts your notes before they leave the device. It is never sent to the server and cannot be recovered — write it down and use the same one on every device."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(passphrase, { passphrase = it }, label = { Text(if (passphraseOnServer == true) "Your existing passphrase" else "New encryption passphrase (8+ characters)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { run { sync.setPassphrase(passphrase); passphrase = ""; "Encryption enabled" } }, enabled = !busy && passphrase.length >= 8) { Text("Unlock sync") }
                            TextButton(onClick = { run { sync.signOut(); null } }, enabled = !busy) { Text("Disconnect account") }
                        }
                    }
                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { run { if (!sync.syncNow()) sync.requestSync(30_000); null } }, enabled = !busy && status.state != SyncState.SYNCING) { Text("Sync now") }
                        TextButton(onClick = { run { sync.signOut(); null } }, enabled = !busy) { Text("Disconnect account") }
                    }
                }

                if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Working…") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to notes") }
            }
        }
    }
}

/** Turns Supabase auth/HTTP failures into a sentence that says what to do next. */
internal fun friendlyAuthError(e: Exception): String {
    val raw = e.message.orEmpty()
    val lower = raw.lowercase()
    return when {
        e is HttpException && ("invalid login credentials" in lower || "invalid_credentials" in lower || "invalid_grant" in lower) ->
            "Wrong email or password. If you have no account yet, use Create account."
        "email not confirmed" in lower || "email_not_confirmed" in lower ->
            "Confirm your email first — open the link we sent you, then connect again."
        "already registered" in lower || "already been registered" in lower || "user_already_exists" in lower ->
            "This email already has an account. Use Connect existing account instead."
        "password should be" in lower || "weak_password" in lower ->
            "Password is too short — use at least 6 characters."
        e is HttpException && e.code == 429 || "rate limit" in lower || "over_email_send_rate_limit" in lower ->
            "Too many attempts. Wait a minute and try again."
        e is HttpException && e.code >= 500 -> "The sync server is having trouble (HTTP ${e.code}). Try again in a few minutes."
        e is java.io.IOException && e !is HttpException -> "No connection. Check the network and try again."
        raw.isNotBlank() -> raw.removePrefix("HTTP ").let { if (it.firstOrNull()?.isDigit() == true) "Server replied: $it" else it }
        else -> "Could not complete this action. Please try again."
    }
}
