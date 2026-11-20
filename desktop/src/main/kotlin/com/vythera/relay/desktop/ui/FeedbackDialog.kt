package com.vythera.relay.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.desktop.DesktopFeedback
import com.vythera.relay.desktop.FeedbackType
import com.vythera.relay.desktop.resources.*
import java.io.File
import org.jetbrains.compose.resources.stringResource

/** The four ways to reach the developer, each opening its own form on the feedback website. */
@Composable
fun FeedbackDialog(onDismiss: () -> Unit) {
    fun open(type: FeedbackType) {
        DesktopFeedback.open(type)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 520.dp),
        shape = RelayShapes.Dialog,
        title = { Text(stringResource(Res.string.settings_feedback_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FeedbackChoice(Icons.Rounded.BugReport, stringResource(Res.string.feedback_bug_title), stringResource(Res.string.feedback_bug_body)) { open(FeedbackType.Bug) }
                FeedbackChoice(Icons.Rounded.Lightbulb, stringResource(Res.string.feedback_feature_title), stringResource(Res.string.feedback_feature_body)) { open(FeedbackType.Feature) }
                FeedbackChoice(Icons.AutoMirrored.Rounded.Chat, stringResource(Res.string.feedback_message_title), stringResource(Res.string.feedback_message_body)) { open(FeedbackType.Message) }
                FeedbackChoice(Icons.Rounded.Star, stringResource(Res.string.feedback_rate_title), stringResource(Res.string.feedback_rate_body)) { open(FeedbackType.Rate) }
                Text(
                    stringResource(Res.string.feedback_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.feedback_close)) } },
    )
}

@Composable
private fun FeedbackChoice(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, shape = RelayShapes.ExtraSmall, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** After something went wrong last time: where the report is, and a way to send it. */
@Composable
fun CrashReportDialog(report: File, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 520.dp),
        shape = RelayShapes.Dialog,
        icon = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
        title = { Text(stringResource(Res.string.crash_title)) },
        text = { Text(stringResource(Res.string.crash_body, report.parentFile?.path ?: report.path)) },
        confirmButton = {
            TextButton(onClick = {
                DesktopFeedback.open(FeedbackType.Bug)
                // The report beside the form, ready to drag onto it.
                runCatching { java.awt.Desktop.getDesktop().open(report.parentFile) }
                onDismiss()
            }) { Text(stringResource(Res.string.crash_report)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.feedback_close)) } },
    )
}
