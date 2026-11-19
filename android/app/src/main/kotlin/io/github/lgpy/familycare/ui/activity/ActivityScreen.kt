package io.github.lgpy.familycare.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.Locale as ComposeLocale
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.ui.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.koin.compose.viewmodel.koinViewModel

/**
 * More → Activity (D30): the circle's audit trail and security events, newest
 * first, grouped by day. Owners only; the read needs a connection because the
 * audit log is not synced.
 */
@Composable
fun ActivityScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActivityViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    // Compose's locale is observable; reading it from the configuration is not (lint).
    val locale = java.util.Locale.forLanguageTag(ComposeLocale.current.toLanguageTag())
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.activity_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        TextButton(onClick = viewModel::refresh) {
            Text(text = stringResource(R.string.activity_refresh))
        }

        uiState.error?.let { error ->
            Text(
                text = stringResource(error.messageRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (uiState.events.isEmpty()) {
            if (!uiState.loading && uiState.error == null) {
                Text(
                    text = stringResource(R.string.activity_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                activityDays(uiState.events, java.time.ZoneId.systemDefault()).forEach { day ->
                    item(key = day.date.toString()) {
                        Text(
                            text = dateFormat.format(day.date),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(day.events, key = { it.id }) { event ->
                        val sentence = activitySentence(
                            event = event,
                            someone = stringResource(R.string.activity_someone),
                        )
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(
                                    sentence.template,
                                    *sentence.args.toTypedArray(),
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = event.occurredAt
                                    .toEpochMilliseconds()
                                    .let { java.time.Instant.ofEpochMilli(it) }
                                    .atZone(java.time.ZoneId.systemDefault())
                                    .format(timeFormat),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        HorizontalDivider()
                    }
                }
                if (!uiState.endReached) {
                    item(key = "load-more") {
                        TextButton(
                            onClick = viewModel::loadMore,
                            enabled = !uiState.loading,
                        ) {
                            Text(text = stringResource(R.string.activity_load_more))
                        }
                    }
                }
            }
        }

        TextButton(onClick = onBack) {
            Text(text = stringResource(R.string.back))
        }
    }
}

/** The message for an error the feed can report. */
private fun CoreError.messageRes(): Int = when (this) {
    CoreError.Offline -> R.string.activity_offline
    CoreError.NotAllowed -> R.string.activity_not_allowed
    CoreError.NoCircle -> R.string.activity_not_allowed
    else -> R.string.activity_error
}
