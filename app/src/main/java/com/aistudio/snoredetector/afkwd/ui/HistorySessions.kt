package com.aistudio.snoredetector.afkwd.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.snoredetector.afkwd.data.SnoreEvent
import com.aistudio.snoredetector.afkwd.data.SnoreSession
import com.aistudio.snoredetector.afkwd.data.formatSessionRange

/**
 * Emits each session as a [SessionHeader] followed by its events.
 */
fun LazyListScope.sessionItems(
    sessions: List<SnoreSession>,
    eventContent: @Composable LazyItemScope.(SnoreEvent) -> Unit
) {
    sessions.forEachIndexed { index, session ->
        item(key = "session_${session.startMillis}") {
            SessionHeader(session = session, showDivider = index > 0)
        }
        items(session.events, key = { it.id }) { event ->
            eventContent(event)
        }
    }
}

/**
 * Separator shown above the events of a session: its time span and number of incidents.
 */
@Composable
fun SessionHeader(session: SnoreSession, showDivider: Boolean) {
    val count = session.events.size
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("session_header_${session.startMillis}")
            .semantics(mergeDescendants = true) {}
    ) {
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
                color = MaterialTheme.colorScheme.outline
            )
        }
        Text(
            text = formatSessionRange(session),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = if (count == 1) "1 incident" else "$count incidents",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
