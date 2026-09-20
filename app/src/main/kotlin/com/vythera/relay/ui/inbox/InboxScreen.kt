package com.vythera.relay.ui.inbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vythera.relay.R
import com.vythera.relay.Texts
import com.vythera.relay.content.ContentCategory
import com.vythera.relay.ui.home.listPosition
import com.vythera.relay.data.db.InboxItemEntity
import com.vythera.relay.designsystem.component.RelayActionEmphasis
import com.vythera.relay.designsystem.component.RelayActionSize
import com.vythera.relay.designsystem.component.RelayActionTile
import com.vythera.relay.designsystem.component.RelayActivityItem
import com.vythera.relay.designsystem.component.RelayEmptyState
import com.vythera.relay.designsystem.component.RelayExpressiveSheet
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.designsystem.theme.RelaySpacing

private enum class InboxFilter(val label: Int, val categories: Set<ContentCategory>?) {
    All(R.string.inbox_all, null),
    Photos(R.string.inbox_photos, setOf(ContentCategory.PHOTOS, ContentCategory.VIDEOS)),
    Documents(R.string.inbox_documents, setOf(ContentCategory.DOCUMENTS, ContentCategory.FOLDER)),
    Links(R.string.inbox_links, setOf(ContentCategory.LINKS)),
    Text(R.string.inbox_text, setOf(ContentCategory.TEXT, ContentCategory.CLIPBOARD)),
    Apps(R.string.inbox_apps, setOf(ContentCategory.APPS)),
}

/**
 * Everything that arrived here, kept until the user is ready for it, so receiving never
 * forces an immediate decision.
 */
@Composable
fun InboxScreen(
    items: List<InboxItemEntity>,
    onCopy: (String) -> Unit,
    onDelete: (Long) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    horizontalMargin: Dp = 20.dp,
) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(InboxFilter.All) }
    var selected by remember { mutableStateOf<InboxItemEntity?>(null) }
    val shown = items.filter { item -> filter.categories?.contains(ContentCategory.fromName(item.category)) ?: true }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = horizontalMargin,
            end = horizontalMargin,
            top = contentPadding.calculateTopPadding() + RelaySpacing.xxl,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        item(key = "title") {
            Column(Modifier.padding(bottom = RelaySpacing.l)) {
                Text(stringResource(R.string.inbox_title), style = MaterialTheme.typography.displayMediumEmphasized, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.inbox_subtitle), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(RelaySpacing.l))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InboxFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(stringResource(option.label), style = MaterialTheme.typography.labelLarge) },
                            shape = RelayShapes.Pill,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            modifier = Modifier.height(40.dp),
                        )
                    }
                }
            }
        }
        if (shown.isEmpty()) {
            item(key = "empty") {
                RelayEmptyState(stringResource(R.string.inbox_empty_title), stringResource(R.string.inbox_empty_body))
            }
        }
        itemsIndexed(shown, key = { _, item -> item.id }) { index, item ->
            RelayActivityItem(
                title = item.title,
                fromName = item.fromName,
                toName = stringResource(R.string.activity_this_device),
                timeLabel = Texts.relativeTime(context, item.receivedAtMillis),
                kind = ContentCategory.fromName(item.category).kind,
                position = listPosition(index, shown.size),
                statusLabel = if (item.uri != null) Texts.size(context, item.sizeBytes) else null,
                onClick = { selected = item },
                modifier = Modifier.animateItem(),
            )
        }
    }

    selected?.let { item ->
        InboxItemSheet(
            item = item,
            onDismiss = { selected = null },
            onCopy = { onCopy(it); selected = null },
            onDelete = { onDelete(item.id); selected = null },
        )
    }
}

@Composable
private fun InboxItemSheet(item: InboxItemEntity, onDismiss: () -> Unit, onCopy: (String) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    RelayExpressiveSheet(
        onDismissRequest = onDismiss,
        title = item.title,
        supportingText = stringResource(R.string.inbox_from, item.fromName),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val openable = item.uri != null || item.text?.let(com.vythera.relay.content.Links::isWebLink) == true
            if (openable) {
                RelayActionTile(stringResource(R.string.inbox_open), Icons.AutoMirrored.Rounded.OpenInNew, { open(context, item); onDismiss() }, Modifier.fillMaxWidth(), emphasis = RelayActionEmphasis.Primary, size = RelayActionSize.Medium, badgeShape = MaterialShapes.Cookie9Sided)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val text = item.text
                if (text != null) {
                    RelayActionTile(stringResource(R.string.inbox_copy), Icons.Rounded.ContentCopy, { onCopy(text) }, Modifier.weight(1f), emphasis = RelayActionEmphasis.Secondary, size = RelayActionSize.Compact, badgeShape = MaterialShapes.Gem)
                }
                RelayActionTile(stringResource(R.string.inbox_share), Icons.Rounded.Share, { share(context, item); onDismiss() }, Modifier.weight(1f), emphasis = RelayActionEmphasis.Secondary, size = RelayActionSize.Compact, badgeShape = MaterialShapes.Clover4Leaf)
                RelayActionTile(stringResource(R.string.inbox_delete), Icons.Rounded.Delete, onDelete, Modifier.weight(1f), emphasis = RelayActionEmphasis.Neutral, size = RelayActionSize.Compact, badgeShape = MaterialShapes.Square)
            }
            val body = item.text
            if (body != null && item.uri == null) {
                Spacer(Modifier.height(8.dp))
                Text(body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

private fun open(context: Context, item: InboxItemEntity) {
    val intent = when {
        item.uri != null -> Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.uri), item.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        item.text != null -> Intent(Intent.ACTION_VIEW, Uri.parse(item.text.orEmpty().trim()))
        else -> return
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, R.string.inbox_no_app, Toast.LENGTH_SHORT).show()
    }
}

private fun share(context: Context, item: InboxItemEntity) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        if (item.uri != null) {
            type = item.mimeType ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, item.text)
        }
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}
