package com.vythera.relay.ui.send

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vythera.relay.R
import com.vythera.relay.ui.device.SendAction
import com.vythera.relay.designsystem.component.RelayActionEmphasis
import com.vythera.relay.designsystem.component.RelayActionSize
import com.vythera.relay.designsystem.component.RelayActionTile
import com.vythera.relay.designsystem.component.RelayExpressiveSheet
import com.vythera.relay.designsystem.component.RelaySendButton
import com.vythera.relay.designsystem.component.RelaySendButtonSize
import com.vythera.relay.designsystem.theme.LocalRelayMotion
import com.vythera.relay.designsystem.theme.RelayShapes

/** The system pickers Relay sends from. No storage permission needed for any of them. */
class SendLaunchers(
    val photos: () -> Unit,
    val files: () -> Unit,
    val folder: () -> Unit,
)

@Composable
fun rememberSendLaunchers(onUris: (List<Uri>) -> Unit, onFolder: (Uri) -> Unit): SendLaunchers {
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { if (it.isNotEmpty()) onUris(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) onUris(it) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(onFolder) }
    return remember(photos, files, folder) {
        SendLaunchers(
            photos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            files = { files.launch(arrayOf("*/*")) },
            folder = { folder.launch(null) },
        )
    }
}

enum class ComposeMode { Text, Link, Continue }

/**
 * "What do you want to send?" The two things people send most are large; everything
 * else is a pill. Text and links are typed right here: the grid slides away and the
 * composer takes its place, so there is no second dialog.
 */
@Composable
fun SendSheet(
    deviceName: String,
    onDismiss: () -> Unit,
    onPick: (SendAction) -> Unit,
    onSendText: (String, ComposeMode) -> Unit,
    initialMode: ComposeMode? = null,
) {
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    val motion = LocalRelayMotion.current
    RelayExpressiveSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.send_title),
        supportingText = stringResource(R.string.send_to, deviceName),
    ) {
        AnimatedContent(
            targetState = mode,
            transitionSpec = {
                val forward = targetState != null
                (slideInHorizontally(motion.spatial()) { if (forward) it / 3 else -it / 3 } + fadeIn(motion.effects())) togetherWith
                    (slideOutHorizontally(motion.spatial()) { if (forward) -it / 3 else it / 3 } + fadeOut(motion.effectsFast()))
            },
            label = "sendSheetMode",
        ) { current ->
            if (current == null) {
                SendChoices(deviceName, onPick = { action ->
                    when (action) {
                        SendAction.Text -> mode = ComposeMode.Text
                        SendAction.Link -> mode = ComposeMode.Link
                        SendAction.Continue -> mode = ComposeMode.Continue
                        else -> onPick(action)
                    }
                })
            } else {
                Composer(current, onSend = { text -> onSendText(text, current) })
            }
        }
    }
}

@Composable
private fun SendChoices(deviceName: String, onPick: (SendAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RelayActionTile(stringResource(R.string.send_photos), Icons.Rounded.Photo, { onPick(SendAction.Photos) }, Modifier.weight(1.1f), stringResource(R.string.send_photos_hint), RelayActionEmphasis.Primary, RelayActionSize.Large, MaterialShapes.Flower)
            RelayActionTile(stringResource(R.string.send_files), Icons.Rounded.Description, { onPick(SendAction.Files) }, Modifier.weight(1f), stringResource(R.string.send_files_hint), RelayActionEmphasis.Tertiary, RelayActionSize.Large, MaterialShapes.Square)
        }
        RelayActionTile(stringResource(R.string.send_clipboard), Icons.Rounded.ContentPaste, { onPick(SendAction.Clipboard) }, Modifier.fillMaxWidth(), stringResource(R.string.send_clipboard_hint), RelayActionEmphasis.Secondary, RelayActionSize.Medium, MaterialShapes.Gem)
        RelayActionTile(stringResource(R.string.send_continue), Icons.AutoMirrored.Rounded.OpenInNew, { onPick(SendAction.Continue) }, Modifier.fillMaxWidth(), stringResource(R.string.send_continue_hint, deviceName), RelayActionEmphasis.Neutral, RelayActionSize.Medium, MaterialShapes.Cookie7Sided)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            RelayActionTile(stringResource(R.string.send_text), Icons.AutoMirrored.Rounded.TextSnippet, { onPick(SendAction.Text) }, Modifier.weight(1f), emphasis = RelayActionEmphasis.Neutral, size = RelayActionSize.Compact, badgeShape = MaterialShapes.Ghostish)
            RelayActionTile(stringResource(R.string.send_link), Icons.Rounded.Link, { onPick(SendAction.Link) }, Modifier.weight(1f), emphasis = RelayActionEmphasis.Neutral, size = RelayActionSize.Compact, badgeShape = MaterialShapes.Cookie4Sided)
            RelayActionTile(stringResource(R.string.send_folder), Icons.Rounded.Folder, { onPick(SendAction.Folder) }, Modifier.weight(1f), emphasis = RelayActionEmphasis.Neutral, size = RelayActionSize.Compact, badgeShape = MaterialShapes.Bun)
        }
    }
}

@Composable
private fun Composer(mode: ComposeMode, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val isLink = mode != ComposeMode.Text
    Column {
        TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = {
                Text(
                    stringResource(if (isLink) R.string.send_link_placeholder else R.string.send_text_placeholder),
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            textStyle = MaterialTheme.typography.headlineSmall,
            shape = RelayShapes.Small,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = if (isLink) KeyboardType.Uri else KeyboardType.Text),
            singleLine = isLink,
            modifier = Modifier.fillMaxWidth().heightIn(min = if (isLink) 72.dp else 160.dp).focusRequester(focus),
        )
        Spacer(Modifier.height(16.dp))
        RelaySendButton(
            onClick = { if (text.isNotBlank()) onSend(text.trim()) },
            text = stringResource(if (mode == ComposeMode.Continue) R.string.send_open_there else R.string.send_button),
            icon = if (mode == ComposeMode.Continue) Icons.AutoMirrored.Rounded.OpenInNew else Icons.AutoMirrored.Rounded.Send,
            size = RelaySendButtonSize.Large,
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
