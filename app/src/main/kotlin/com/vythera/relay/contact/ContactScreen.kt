package com.vythera.relay.contact

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.vythera.relay.R
import com.vythera.relay.designsystem.component.RelayExpressiveSheet
import com.vythera.relay.designsystem.component.RelayHandoffIllustration
import com.vythera.relay.designsystem.component.RelaySendButton
import com.vythera.relay.designsystem.component.RelaySendButtonSize
import com.vythera.relay.designsystem.component.rememberRelayHaptics
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.designsystem.theme.RelaySpacing
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * "Tap phones to swap contacts."
 *
 * While this screen is open the phone both offers its card (NFC card emulation) and
 * looks for the other phone's card (NFC reader). A phone cannot do both at the same
 * instant, so it alternates at random intervals; two phones held together fall into
 * opposite roles within a second and swap cards in one exchange.
 *
 * Phones without NFC, and phones without Relay, use the QR code: any camera app turns
 * it into "Add contact".
 */
@Composable
fun ContactScreen(
    card: ContactCard,
    onSaveCard: (ContactCard) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    horizontalMargin: Dp = 20.dp,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val haptics = rememberRelayHaptics()
    val colors = MaterialTheme.colorScheme
    val adapter = remember { NfcAdapter.getDefaultAdapter(context) }
    var nfcEnabled by remember { mutableStateOf(adapter?.isEnabled == true) }
    var editing by rememberSaveable { mutableStateOf(card.isEmpty) }
    var received by remember { mutableStateOf<ContactCard?>(null) }
    val vcard = remember(card) { card.toVCard() }

    LifecycleResumeEffect(adapter) {
        nfcEnabled = adapter?.isEnabled == true
        onPauseOrDispose { }
    }

    // Offer our card over NFC only while this screen is showing and the card has content.
    DisposableEffect(vcard, card.isEmpty) {
        ContactExchange.sharing = if (card.isEmpty) null else vcard.encodeToByteArray()
        onDispose { ContactExchange.sharing = null }
    }

    LaunchedEffect(Unit) {
        ContactExchange.received.collect {
            haptics.confirm()
            received = it
        }
    }

    // Alternate between reading and being read, so two phones on this screen meet.
    LaunchedEffect(adapter, activity, nfcEnabled, card.isEmpty) {
        if (adapter == null || activity == null || !nfcEnabled || card.isEmpty) return@LaunchedEffect
        val own = vcard.encodeToByteArray()
        val callback = NfcAdapter.ReaderCallback { tag: Tag ->
            val isoDep = IsoDep.get(tag) ?: return@ReaderCallback
            runCatching {
                isoDep.use {
                    it.connect()
                    it.timeout = 2_000
                    ContactExchange.onReceived(CardExchange.exchange(it::transceive, own))
                }
            }
        }
        try {
            while (true) {
                adapter.enableReaderMode(activity, callback, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
                delay(Random.nextLong(450, 900))
                adapter.disableReaderMode(activity)
                delay(Random.nextLong(350, 750))
            }
        } finally {
            runCatching { adapter.disableReaderMode(activity) }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = horizontalMargin)
            .padding(top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 32.dp),
    ) {
        FilledTonalIconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        Spacer(Modifier.height(RelaySpacing.l))
        Text(stringResource(R.string.contact_title), style = MaterialTheme.typography.displayMediumEmphasized, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                when {
                    adapter == null -> R.string.contact_subtitle_no_nfc
                    !nfcEnabled -> R.string.contact_subtitle_nfc_off
                    else -> R.string.contact_subtitle_tap
                },
            ),
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurfaceVariant,
        )
        if (adapter != null && !nfcEnabled) {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } },
                shapes = ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall),
            ) {
                Icon(Icons.Rounded.Nfc, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.contact_turn_on_nfc))
            }
        }

        if (adapter != null && nfcEnabled && !card.isEmpty) {
            Spacer(Modifier.height(RelaySpacing.l))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RelayHandoffIllustration(size = 220.dp) }
        }

        Spacer(Modifier.height(RelaySpacing.xl))
        CardPreview(card, onEdit = { editing = true })

        if (!card.isEmpty) {
            Spacer(Modifier.height(RelaySpacing.xl))
            Text(stringResource(R.string.contact_qr_title), style = MaterialTheme.typography.titleLargeEmphasized)
            Text(stringResource(R.string.contact_qr_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(RelaySpacing.m))
            QrCode(vcard, Modifier.fillMaxWidth())
        }
    }

    if (editing) {
        EditCardSheet(card, onDismiss = { editing = false }, onSave = { onSaveCard(it); editing = false })
    }

    received?.let { peer ->
        ReceivedCardSheet(
            card = peer,
            onSave = {
                runCatching { context.startActivity(peer.insertIntent()) }
                received = null
            },
            onDismiss = { received = null },
        )
    }
}

@Composable
private fun CardPreview(card: ContactCard, onEdit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onEdit, shape = RelayShapes.Hero, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.contact_my_card), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.contact_edit))
            }
            Spacer(Modifier.height(12.dp))
            Text(card.name.ifBlank { stringResource(R.string.contact_add_details) }, style = MaterialTheme.typography.headlineLargeEmphasized)
            listOf(card.phone, card.email, card.organization).filter { it.isNotBlank() }.forEach {
                Text(it, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * The card as a QR code. Kept dark-on-white whatever the theme, because that is what
 * camera apps read reliably, and set on a rounded white plate so it sits in the design.
 */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8"))
    }
    val description = stringResource(R.string.contact_qr_title)
    Box(modifier.background(Color.White, RelayShapes.Action).padding(24.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(260.dp)) {
            val cell = size.minDimension / matrix.width
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    // Plain, slightly overlapping squares: rounded modules leave hairline
                    // gaps that break the finder patterns for stricter decoders.
                    drawRect(
                        color = Color(0xFF1A120E),
                        topLeft = Offset(x * cell, y * cell),
                        size = Size(cell + 0.75f, cell + 0.75f),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditCardSheet(card: ContactCard, onDismiss: () -> Unit, onSave: (ContactCard) -> Unit) {
    var name by rememberSaveable { mutableStateOf(card.name) }
    var phone by rememberSaveable { mutableStateOf(card.phone) }
    var email by rememberSaveable { mutableStateOf(card.email) }
    var organization by rememberSaveable { mutableStateOf(card.organization) }
    RelayExpressiveSheet(onDismissRequest = onDismiss, title = stringResource(R.string.contact_edit_title), supportingText = stringResource(R.string.contact_edit_body)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(name, { name = it }, R.string.contact_field_name, KeyboardType.Text)
            Field(phone, { phone = it }, R.string.contact_field_phone, KeyboardType.Phone)
            Field(email, { email = it }, R.string.contact_field_email, KeyboardType.Email)
            Field(organization, { organization = it }, R.string.contact_field_organization, KeyboardType.Text)
            Spacer(Modifier.height(8.dp))
            RelaySendButton(
                onClick = { onSave(ContactCard(name.trim(), phone.trim(), email.trim(), organization.trim())) },
                text = stringResource(R.string.settings_save),
                icon = Icons.Rounded.Edit,
                size = RelaySendButtonSize.Large,
                enabled = name.isNotBlank() && (phone.isNotBlank() || email.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: Int, keyboard: KeyboardType) {
    TextField(
        value = value,
        onValueChange = { if (it.length <= ContactCard.MAX_FIELD_LENGTH) onChange(it) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        shape = RelayShapes.Small,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ReceivedCardSheet(card: ContactCard, onSave: () -> Unit, onDismiss: () -> Unit) {
    RelayExpressiveSheet(onDismissRequest = onDismiss, title = card.name.ifBlank { stringResource(R.string.contact_received_title) }) {
        listOf(card.phone, card.email, card.organization).filter { it.isNotBlank() }.forEach {
            Text(it, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(RelaySpacing.xl))
        RelaySendButton(onClick = onSave, text = stringResource(R.string.contact_save), icon = Icons.Rounded.PersonAdd, modifier = Modifier.fillMaxWidth())
    }
}

/** Opens the system's "new contact" screen pre-filled. No contacts permission needed. */
private fun ContactCard.insertIntent(): Intent =
    Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
        if (name.isNotBlank()) putExtra(ContactsContract.Intents.Insert.NAME, name)
        if (phone.isNotBlank()) putExtra(ContactsContract.Intents.Insert.PHONE, phone)
        if (email.isNotBlank()) putExtra(ContactsContract.Intents.Insert.EMAIL, email)
        if (organization.isNotBlank()) putExtra(ContactsContract.Intents.Insert.COMPANY, organization)
    }
