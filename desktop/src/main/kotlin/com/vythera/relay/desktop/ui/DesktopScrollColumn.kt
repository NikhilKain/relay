package com.vythera.relay.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A visible, draggable scrollbar for every desktop page and independent device pane. */
@Composable
internal fun DesktopScrollColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 18.dp).padding(contentPadding),
            verticalArrangement = verticalArrangement, content = content)
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp, horizontal = 4.dp),
            style = LocalScrollbarStyle.current.copy(thickness = 10.dp,
                unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                hoverColor = MaterialTheme.colorScheme.primary),
        )
    }
}
