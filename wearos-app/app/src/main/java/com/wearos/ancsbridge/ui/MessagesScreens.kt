package com.wearos.ancsbridge.ui

import android.app.RemoteInput
import android.content.Intent
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import com.wearos.ancsbridge.messages.ClassicPairing
import com.wearos.ancsbridge.messages.Contact
import com.wearos.ancsbridge.messages.Conversation
import com.wearos.ancsbridge.messages.MapFormats
import com.wearos.ancsbridge.messages.PhoneMessaging
import com.wearos.ancsbridge.model.QuickReplies
import com.wearos.ancsbridge.viewmodel.MainViewModel
import java.text.DateFormat
import java.util.Date

internal object MessageColors {
    val Green = Color(0xFF34C759)
    val Outgoing = Color(0xFF0A84FF)
    val Incoming = Color(0xFF2C2C2E)
}

internal fun shortTime(ms: Long, now: Long = System.currentTimeMillis()): String = when {
    ms <= 0 -> ""
    now - ms < 60_000 -> "now"
    now - ms < 86_400_000 -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
    else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ms))
}

// ---------------------------------------------------------------------------------------
// Conversation list
// ---------------------------------------------------------------------------------------

@Composable
internal fun MessagesScreen(
    viewModel: MainViewModel,
    openConversation: (String) -> Unit,
    openCompose: () -> Unit,
    openSetup: () -> Unit,
) {
    val ready = remember { viewModel.messagingReady() }
    val conversations by viewModel.conversations.collectAsState()
    val status by viewModel.messagingStatus.collectAsState()
    LaunchedEffect(ready) { if (ready) viewModel.refreshMessages() }
    if (!ready) {
        MessagesIntroContent(onSetup = openSetup)
        return
    }
    MessagesContent(conversations, status, openConversation, openCompose, onRefresh = { viewModel.refreshMessages() })
}

@Composable
internal fun MessagesIntroContent(onSetup: () -> Unit) {
    ListScreen {
        item { ListHeader { Text("Messages") } }
        item {
            Text(
                "Read and send iMessage and SMS from your watch. It uses the same Bluetooth messaging that cars use, so the watch needs a one-time Bluetooth pairing with your iPhone.",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = Color(0xFF98989F), modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        item {
            Button(onClick = onSetup, modifier = Modifier.fillMaxWidth(),
                icon = { Icon(Icons.Rounded.Bluetooth, contentDescription = null) }) { Text("Set Up") }
        }
    }
}

@Composable
internal fun MessagesContent(
    conversations: List<Conversation>,
    status: PhoneMessaging.Status,
    openConversation: (String) -> Unit,
    openCompose: () -> Unit,
    onRefresh: () -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    ListScreen {
        item { ListHeader { Text("Messages") } }
        item {
            Button(
                onClick = openCompose,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MessageColors.Green, contentColor = Color.White, iconColor = Color.White),
                icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
            ) { Text("New Message") }
        }
        when (status) {
            is PhoneMessaging.Status.Working -> item { CircularProgressIndicator(Modifier.size(22.dp)) }
            is PhoneMessaging.Status.Error -> item {
                Text(status.message, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9F0A),
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
            }
            else -> Unit
        }
        if (conversations.isEmpty() && status !is PhoneMessaging.Status.Working) item {
            Text("No messages yet", style = MaterialTheme.typography.bodySmall, color = Color(0xFF98989F))
        }
        conversations.take(30).forEach { conversation ->
            item(key = conversation.address) {
                Button(
                    onClick = { openConversation(conversation.address) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Avatar(conversation.name, conversation.unread > 0) },
                    secondaryLabel = {
                        Text(conversation.last.let { (if (it.outgoing) "You: " else "") + it.subject },
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                ) {
                    Text(conversation.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(shortTime(conversation.last.timeMs, now), fontSize = 10.sp, color = Color(0xFF98989F))
                }
            }
        }
        item {
            NavButton(Icons.Rounded.Refresh, Color(0xFF636366), "Refresh", null, onRefresh)
        }
    }
}

@Composable
private fun Avatar(name: String, unread: Boolean) {
    Box(contentAlignment = Alignment.TopStart) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(Color(0xFF636366)),
            contentAlignment = Alignment.Center
        ) {
            val initials = name.split(' ').filter { it.isNotBlank() && it[0].isLetter() }
                .take(2).joinToString("") { it.first().uppercase() }
            if (initials.isNotEmpty()) Text(initials, fontSize = 12.sp, color = Color.White)
            else Icon(Icons.Rounded.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        if (unread) Box(Modifier.size(9.dp).clip(CircleShape).background(MessageColors.Outgoing))
    }
}

// ---------------------------------------------------------------------------------------
// Conversation
// ---------------------------------------------------------------------------------------

@Composable
internal fun ConversationScreen(viewModel: MainViewModel, address: String, openReply: () -> Unit) {
    val conversations by viewModel.conversations.collectAsState()
    val conversation = conversations.firstOrNull { MapFormats.sameAddress(it.address, address) }
    ConversationContent(
        name = conversation?.name ?: address,
        conversation = conversation,
        onReply = openReply,
    )
}

@Composable
internal fun ConversationContent(name: String, conversation: Conversation?, onReply: () -> Unit) {
    ListScreen {
        item { ListHeader { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        conversation?.messages?.takeLast(25)?.forEach { message ->
            item(key = message.handle) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    contentAlignment = if (message.outgoing) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Column(
                        Modifier.widthIn(max = 170.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (message.outgoing) MessageColors.Outgoing else MessageColors.Incoming)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(message.subject, fontSize = 14.sp, color = Color.White)
                        Text(shortTime(message.timeMs), fontSize = 9.sp, color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
        item {
            Button(
                onClick = onReply,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MessageColors.Outgoing, contentColor = Color.White, iconColor = Color.White),
                icon = { Icon(Icons.AutoMirrored.Rounded.Chat, contentDescription = null) },
            ) { Text("Reply") }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Reply: quick replies, or dictation / keyboard / emoji through the system input
// ---------------------------------------------------------------------------------------

@Composable
internal fun ReplyScreen(viewModel: MainViewModel, address: String, name: String, onSent: () -> Unit) {
    val context = LocalContext.current
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun send(text: String) {
        if (text.isBlank() || sending) return
        sending = true
        error = null
        viewModel.sendMessage(address, text) { ok ->
            sending = false
            if (ok) onSent() else {
                error = (PhoneMessaging.status.value as? PhoneMessaging.Status.Error)?.message ?: "Not sent"
            }
        }
    }
    val input = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence("reply")?.toString()
        if (!text.isNullOrBlank()) send(text.trim())
    }
    ReplyContent(
        name = name,
        replies = remember { QuickReplies.load(context) },
        sending = sending,
        error = error,
        onReply = ::send,
        onCompose = {
            val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
            val remoteInput = RemoteInput.Builder("reply").setLabel("Message")
                .wearableExtender {
                    setEmojisAllowed(true)
                    setInputActionType(EditorInfo.IME_ACTION_SEND)
                }.build()
            RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
            input.launch(intent)
        },
    )
}

@Composable
internal fun ReplyContent(
    name: String,
    replies: List<String>,
    sending: Boolean,
    error: String?,
    onReply: (String) -> Unit,
    onCompose: () -> Unit,
) {
    ListScreen {
        item { ListHeader { Text("To: $name", maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        if (sending) item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(26.dp))
                Text("Sending…", style = MaterialTheme.typography.labelSmall)
            }
        }
        error?.let { item {
            Text(it, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9F0A), textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp))
        } }
        item {
            Button(
                onClick = onCompose,
                modifier = Modifier.fillMaxWidth(),
                enabled = !sending,
                colors = ButtonDefaults.buttonColors(containerColor = MessageColors.Outgoing, contentColor = Color.White, iconColor = Color.White),
                icon = { Icon(Icons.Rounded.Keyboard, contentDescription = null) },
                secondaryLabel = { Text("Dictate, type or emoji") },
            ) { Text("Write") }
        }
        replies.forEach { reply ->
            item(key = reply) {
                Button(
                    onClick = { onReply(reply) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !sending,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null) },
                ) { Text(reply, maxLines = 3) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// New message: pick a contact
// ---------------------------------------------------------------------------------------

@Composable
internal fun ComposeScreen(viewModel: MainViewModel, pick: (address: String, name: String) -> Unit) {
    val contacts by viewModel.contacts.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val status by viewModel.messagingStatus.collectAsState()
    ComposeContent(contacts, conversations, status, pick, onSync = { viewModel.syncContacts() })
}

@Composable
internal fun ComposeContent(
    contacts: List<Contact>,
    recent: List<Conversation>,
    status: PhoneMessaging.Status,
    pick: (String, String) -> Unit,
    onSync: () -> Unit,
) {
    var expanded by remember { mutableStateOf<Contact?>(null) }
    ListScreen {
        item { ListHeader { Text("New Message") } }
        if (recent.isNotEmpty()) {
            item { Text("RECENT", style = MaterialTheme.typography.labelSmall, color = Color(0xFF98989F)) }
            recent.take(5).forEach { c ->
                item(key = "recent-" + c.address) {
                    NavButton(Icons.Rounded.Person, Color(0xFF636366), c.name, c.address) { pick(c.address, c.name) }
                }
            }
        }
        item { Text("CONTACTS", style = MaterialTheme.typography.labelSmall, color = Color(0xFF98989F)) }
        if (contacts.isEmpty()) item {
            Button(
                onClick = onSync, modifier = Modifier.fillMaxWidth(),
                enabled = status !is PhoneMessaging.Status.Working,
                icon = { Icon(Icons.Rounded.Contacts, contentDescription = null) },
                secondaryLabel = { Text("Turn on Sync Contacts on iPhone first") },
            ) { Text(if (status is PhoneMessaging.Status.Working) "Syncing…" else "Sync Contacts") }
        }
        if (status is PhoneMessaging.Status.Error) item {
            Text(status.message, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9F0A),
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
        }
        contacts.forEach { contact ->
            item(key = "c-" + contact.name + contact.addresses.firstOrNull()) {
                NavButton(
                    Icons.Rounded.Person, Color(0xFF636366), contact.name,
                    if (contact.addresses.size == 1) contact.addresses.first() else "${contact.addresses.size} numbers"
                ) {
                    if (contact.addresses.size == 1) pick(contact.addresses.first(), contact.name)
                    else expanded = if (expanded == contact) null else contact
                }
            }
            if (expanded == contact) contact.addresses.forEach { address ->
                item(key = "a-" + contact.name + address) {
                    Button(onClick = { pick(address, contact.name) }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C3A5C))) {
                        Text(address, maxLines = 1)
                    }
                }
            }
        }
        if (contacts.isNotEmpty()) item {
            NavButton(Icons.Rounded.Refresh, Color(0xFF636366), "Sync Contacts", null, onSync)
        }
    }
}

// ---------------------------------------------------------------------------------------
// One-time Bluetooth Classic setup
// ---------------------------------------------------------------------------------------

@Composable
internal fun MessagesSetupScreen(viewModel: MainViewModel, onDone: () -> Unit) {
    val pairing = viewModel.classicPairing
    val found by pairing.found.collectAsState()
    val state by pairing.state.collectAsState()
    DisposableEffect(Unit) {
        pairing.start()
        onDispose { pairing.stop() }
    }
    LaunchedEffect(state) {
        if (state is ClassicPairing.State.Paired) {
            viewModel.syncContacts()
            viewModel.refreshMessages()
        }
    }
    MessagesSetupContent(
        found = found.map { it.name to it.device.address },
        state = state,
        onSearch = pairing::start,
        onPick = { address -> found.firstOrNull { it.device.address == address }?.let(pairing::pair) },
        onDone = onDone,
        onForget = viewModel::forgetMessaging,
    )
}

@Composable
internal fun MessagesSetupContent(
    found: List<Pair<String, String>>,
    state: ClassicPairing.State,
    onSearch: () -> Unit,
    onPick: (String) -> Unit,
    onDone: () -> Unit,
    onForget: () -> Unit = {},
) {
    ListScreen {
        item { ListHeader { Text("Set Up Messages") } }
        when (state) {
            is ClassicPairing.State.Paired -> {
                item {
                    Text("Paired with ${state.name}", style = MaterialTheme.typography.titleSmall,
                        color = MessageColors.Green, textAlign = TextAlign.Center)
                }
                item {
                    Text(
                        "On iPhone: Settings › Bluetooth › ⓘ next to this watch › turn on Show Notifications and Sync Contacts.",
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                item { Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") } }
                return@ListScreen
            }
            is ClassicPairing.State.Pairing -> item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(26.dp))
                    Text("Confirm the code on both devices", style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center)
                }
            }
            is ClassicPairing.State.Failed -> item {
                Text(state.message, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9F0A),
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
            }
            else -> item {
                Text(
                    "1. On iPhone, open Settings › Bluetooth and stay on that screen.\n2. Pick your iPhone below.",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF98989F),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
        found.forEach { (name, address) ->
            item(key = address) {
                NavButton(Icons.Rounded.PhoneIphone, Color(0xFF0A84FF), name, address) { onPick(address) }
            }
        }
        item {
            Button(
                onClick = onSearch, modifier = Modifier.fillMaxWidth(),
                enabled = state !is ClassicPairing.State.Searching,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = {
                    if (state is ClassicPairing.State.Searching) CircularProgressIndicator(Modifier.size(20.dp))
                    else Icon(Icons.Rounded.Bluetooth, contentDescription = null)
                },
            ) { Text(if (state is ClassicPairing.State.Searching) "Searching…" else "Search Again") }
        }
        item { NavButton(Icons.Rounded.LinkOff, Color(0xFF636366), "Reset Messages", null, onForget) }
    }
}
