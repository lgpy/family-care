package io.github.lgpy.familycare.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.theme.FamilyCareDimens
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme

/**
 * The action of an [EmptyStateCard]: the primary button that starts the work
 * which fills the screen.
 *
 * @param label The button's words ("Add patient", "Add their first medicine").
 * @param onClick What the button does. The feature supplies it, so the empty
 *   state never invents a flow of its own.
 */
data class EmptyStateAction(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * A neutral card for a screen that has nothing yet, with one sentence about
 * what will appear there and — when the screen already has an action that
 * fills it — a primary button for that action.
 *
 * The card is deliberately quiet (surface container, no accent colour): an
 * empty list is not something that needs attention. The title is the one bold
 * line, the body explains the next step, and the button reuses the screen's
 * existing action rather than adding a second path.
 *
 * Text never truncates: both lines wrap and the card grows. The button keeps
 * the 48 dp touch target from the design system.
 */
@Composable
fun EmptyStateCard(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    action: EmptyStateAction? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FamilyCareDimens.CardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            body?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            action?.let { stateAction ->
                Button(
                    onClick = stateAction.onClick,
                    modifier = Modifier.heightIn(min = FamilyCareDimens.MinTouchTarget),
                ) {
                    Text(text = stateAction.label)
                }
            }
        }
    }
}

@ScalePreviews
@Composable
private fun EmptyStateCardPreview() {
    FamilyCareTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                EmptyStateCard(
                    title = "No medications yet. Add the first one.",
                    action = EmptyStateAction(label = "Add their first medicine", onClick = {}),
                )
                EmptyStateCard(
                    title = "Add the first person you care for",
                    body = "Then add their medicines and Family Care will remind you when doses are due.",
                    action = EmptyStateAction(label = "Add patient", onClick = {}),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyStateCardBodyOnlyPreview() {
    FamilyCareTheme(dynamicColor = false) {
        Surface {
            EmptyStateCard(
                title = "Nothing to buy",
                body = "Medications appear here when they are running low.",
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
