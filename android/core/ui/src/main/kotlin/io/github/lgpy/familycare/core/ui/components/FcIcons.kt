package io.github.lgpy.familycare.core.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme

/**
 * The app's icon set: every Material Symbol bundled in `core/ui` as a vector
 * drawable, exposed by name (D33).
 *
 * `material-icons-extended` is deliberately not a dependency, so the set only
 * contains the symbols the screens use. A feature picks a property from here
 * instead of referencing a drawable itself; the drawables stay an
 * implementation detail of this module.
 *
 * Each property is a [ImageVector] loaded from the drawable's resource through
 * `ImageVector.vectorResource`. The drawables fill with white and carry no tint
 * of their own, so [Icon] tints them with its `tint` (and
 * `MaterialTheme.colorScheme` when it is not given). That also means an icon
 * can be drawn in any colour the design needs.
 *
 * Naming: the drawable `ic_sym_<snake_case>` becomes `<CamelCase>`; the filled
 * variant `ic_sym_<name>_fill1` becomes `<Name>Filled`. Filled variants are for
 * status and the selected tab only (docs/ux/design-system.md §6).
 */
object FcIcons {

    // ---- Navigation and chrome -------------------------------------------

    val ArrowBack: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_arrow_back)
    val ChevronRight: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_chevron_right)
    val ExpandMore: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_expand_more)
    val MoreVert: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_more_vert)
    val MoreHoriz: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_more_horiz)
    val Close: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_close)
    val Add: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_add)
    val AddCircle: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_add_circle)
    val Remove: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_remove)
    val Check: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_check)
    val Cancel: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_cancel)
    val Search: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_search)
    val FilterList: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_filter_list)
    val Tune: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_tune)
    val Edit: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_edit)
    val ContentCopy: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_content_copy)
    val IosShare: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_ios_share)
    val PhotoCamera: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_photo_camera)
    val HorizontalRule: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_horizontal_rule)
    val Language: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_language)
    val Lock: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_lock)

    // ---- Bottom navigation -----------------------------------------------

    val Home: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_home)
    val Menu: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_menu)
    val CalendarMonth: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_calendar_month)
    val ShoppingCart: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_shopping_cart)

    // ---- People and the circle -------------------------------------------

    val Person: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_person)
    val PersonAdd: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_person_add)
    val Contacts: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_contacts)
    val Group: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_group)
    val Groups: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_groups)
    val Badge: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_badge)

    // ---- Medicines, doses and stock --------------------------------------

    val Medication: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_medication)
    val MedicationFilled: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_medication_fill1)
    val Pill: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_pill)
    val Prescriptions: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_prescriptions)
    val Inventory2: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_inventory_2)
    val Package2: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_package_2)
    val AddShoppingCart: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_add_shopping_cart)
    val Schedule: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_schedule)
    val Alarm: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_alarm)
    val Snooze: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_snooze)
    val NotificationsActive: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_notifications_active)
    val NotificationsActiveFilled: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_notifications_active_fill1)
    val NotificationsOff: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_notifications_off)
    val Checklist: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_checklist)
    val Tally3: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_tally_3)

    // ---- Health and conditions -------------------------------------------

    val Stethoscope: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_stethoscope)
    val Bloodtype: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_bloodtype)
    val EcgHeart: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_ecg_heart)
    val Cardiology: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_cardiology)
    val Radiology: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_radiology)
    val Dentistry: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_dentistry)
    val Glucose: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_glucose)
    val Contrast: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_contrast)
    val Emergency: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_emergency)
    val EmergencyFilled: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_emergency_fill1)

    // ---- Status and feedback ---------------------------------------------

    val CheckCircle: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_check_circle)
    val CheckCircleFilled: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_check_circle_fill1)
    val Error: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_error)
    val ErrorFilled: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_error_fill1)
    val Warning: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_warning)
    val WarningFilled: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_warning_fill1)
    val Block: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_block)
    val Info: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_info)

    // ---- Food and meals ---------------------------------------------------

    val Restaurant: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_restaurant)
    val NoFood: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_no_food)
    val DinnerDining: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_dinner_dining)
    val LunchDining: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_lunch_dining)

    // ---- Appointments, history and places --------------------------------

    val Event: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_event)
    val CalendarToday: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_calendar_today)
    val History: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_history)
    val ManageHistory: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_manage_history)
    val LocationOn: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_location_on)

    // ---- Everything else --------------------------------------------------

    val Call: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_call)
    val CallFilled: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_call_fill1)
    val ReceiptLong: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_receipt_long)
    val Folder: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_folder)
    val Category: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_category)
    val StickyNote2: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_sticky_note_2)
    val Palette: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_palette)
    val Bedtime: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_bedtime)
    val WbSunny: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_wb_sunny)

    /**
     * Every icon, name first, in the same order as the properties above.
     *
     * This exists for the preview grid (and any tooling that wants the whole
     * set); a screen still picks a named property. It is internal so the public
     * API stays one property per icon.
     */
    @Composable
    internal fun all(): List<Pair<String, ImageVector>> = listOf(
        // Navigation and chrome.
        "ArrowBack" to ArrowBack,
        "ChevronRight" to ChevronRight,
        "ExpandMore" to ExpandMore,
        "MoreVert" to MoreVert,
        "MoreHoriz" to MoreHoriz,
        "Close" to Close,
        "Add" to Add,
        "AddCircle" to AddCircle,
        "Remove" to Remove,
        "Check" to Check,
        "Cancel" to Cancel,
        "Search" to Search,
        "FilterList" to FilterList,
        "Tune" to Tune,
        "Edit" to Edit,
        "ContentCopy" to ContentCopy,
        "IosShare" to IosShare,
        "PhotoCamera" to PhotoCamera,
        "HorizontalRule" to HorizontalRule,
        "Language" to Language,
        "Lock" to Lock,
        // Bottom navigation.
        "Home" to Home,
        "Menu" to Menu,
        "CalendarMonth" to CalendarMonth,
        "ShoppingCart" to ShoppingCart,
        // People and the circle.
        "Person" to Person,
        "PersonAdd" to PersonAdd,
        "Contacts" to Contacts,
        "Group" to Group,
        "Groups" to Groups,
        "Badge" to Badge,
        // Medicines, doses and stock.
        "Medication" to Medication,
        "MedicationFilled" to MedicationFilled,
        "Pill" to Pill,
        "Prescriptions" to Prescriptions,
        "Inventory2" to Inventory2,
        "Package2" to Package2,
        "AddShoppingCart" to AddShoppingCart,
        "Schedule" to Schedule,
        "Alarm" to Alarm,
        "Snooze" to Snooze,
        "NotificationsActive" to NotificationsActive,
        "NotificationsActiveFilled" to NotificationsActiveFilled,
        "NotificationsOff" to NotificationsOff,
        "Checklist" to Checklist,
        "Tally3" to Tally3,
        // Health and conditions.
        "Stethoscope" to Stethoscope,
        "Bloodtype" to Bloodtype,
        "EcgHeart" to EcgHeart,
        "Cardiology" to Cardiology,
        "Radiology" to Radiology,
        "Dentistry" to Dentistry,
        "Glucose" to Glucose,
        "Contrast" to Contrast,
        "Emergency" to Emergency,
        "EmergencyFilled" to EmergencyFilled,
        // Status and feedback.
        "CheckCircle" to CheckCircle,
        "CheckCircleFilled" to CheckCircleFilled,
        "Error" to Error,
        "ErrorFilled" to ErrorFilled,
        "Warning" to Warning,
        "WarningFilled" to WarningFilled,
        "Block" to Block,
        "Info" to Info,
        // Food and meals.
        "Restaurant" to Restaurant,
        "NoFood" to NoFood,
        "DinnerDining" to DinnerDining,
        "LunchDining" to LunchDining,
        // Appointments, history and places.
        "Event" to Event,
        "CalendarToday" to CalendarToday,
        "History" to History,
        "ManageHistory" to ManageHistory,
        "LocationOn" to LocationOn,
        // Everything else.
        "Call" to Call,
        "CallFilled" to CallFilled,
        "ReceiptLong" to ReceiptLong,
        "Folder" to Folder,
        "Category" to Category,
        "StickyNote2" to StickyNote2,
        "Palette" to Palette,
        "Bedtime" to Bedtime,
        "WbSunny" to WbSunny,
    )
}

/**
 * Every icon in [FcIcons], tinted with the theme's primary colour.
 *
 * This is also the check that `Icon(FcIcons.X)` tints the bundled drawables: if
 * it did not, the grid would render the drawables' white fill instead of the
 * primary colour. The labels are the property names, so a missing or
 * mis-cased icon is visible here.
 */
@Preview(name = "Icons light", widthDp = 384, showBackground = true)
@Preview(
    name = "Icons dark",
    widthDp = 384,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun FcIconsPreview() {
    FamilyCareTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FcIcons.all().chunked(8).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (name, icon) ->
                            Column(
                                modifier = Modifier.width(36.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp),
                                )
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
