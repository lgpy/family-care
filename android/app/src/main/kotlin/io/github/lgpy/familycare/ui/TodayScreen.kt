package io.github.lgpy.familycare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.CareCircleId
import io.github.lgpy.familycare.core.ui.R
import org.koin.compose.koinInject

@Composable
fun TodayScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val careCircleId = koinInject<CareCircleId>()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.today_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.today_body, careCircleId.value),
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onOpenSettings) {
            Text(text = stringResource(R.string.settings_open))
        }
    }
}
