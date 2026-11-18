package io.github.lgpy.familycare.feature.patientinfo.data

import com.powersync.db.SqlCursor
import com.powersync.db.getString
import com.powersync.db.getStringOptional
import io.github.lgpy.familycare.feature.patientinfo.domain.EmergencyContact
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One `patient_info` row, before it becomes the domain [PatientInfo].
 *
 * Kept separate from the cursor reading so the interesting part — the three
 * `jsonb` columns — is unit-tested without a PowerSync database.
 *
 * A malformed JSON value drops only the entries it cannot read: an allergy the
 * app can show must still be shown, because an empty banner is a claim about
 * the patient, not a neutral blank.
 */
internal data class PatientInfoRow(
    val id: String,
    val patientId: String,
    val allergies: String,
    val conditions: String,
    val snsNumber: String?,
    val familyDoctor: String?,
    val healthCentre: String?,
    val healthCentrePhone: String?,
    val emergencyContacts: String,
    val notes: String?,
) {

    fun toPatientInfo(): PatientInfo = PatientInfo(
        allergies = parseTextList(allergies),
        conditions = parseTextList(conditions),
        snsNumber = snsNumber,
        familyDoctor = familyDoctor,
        healthCentre = healthCentre,
        healthCentrePhone = healthCentrePhone,
        emergencyContacts = parseContacts(emergencyContacts),
        notes = notes,
    )
}

/** Reads one row of [SELECT_PATIENT_INFO]. */
internal fun SqlCursor.toPatientInfoRow(): PatientInfoRow = PatientInfoRow(
    id = getString("id"),
    patientId = getString("patient_id"),
    allergies = getString("allergies"),
    conditions = getString("conditions"),
    snsNumber = getStringOptional("sns_number"),
    familyDoctor = getStringOptional("family_doctor"),
    healthCentre = getStringOptional("health_centre"),
    healthCentrePhone = getStringOptional("health_centre_phone"),
    emergencyContacts = getString("emergency_contacts"),
    notes = getStringOptional("notes"),
)

/** The row as the domain type, or null when the patient is not local (anymore). */
internal fun List<PatientInfoRow>.toPatientInfo(): PatientInfo? = firstOrNull()?.toPatientInfo()

/*
 * The JSON text forms below are the contract with the server. Postgres
 * re-serialises `jsonb` on the way back (keys reordered, spaces added), so the
 * exact bytes are not stable: only the structure is.
 */

/** A text list as the JSON Postgres stores: a compact array of strings. */
internal fun formatTextList(values: List<String>): String =
    JsonArray(values.map { JsonPrimitive(it) }).toString()

/** A text list from SQLite; unreadable entries are dropped, a non-array is empty. */
internal fun parseTextList(json: String?): List<String> {
    val text = json?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val array = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull()
        ?: return emptyList()

    return array.mapNotNull { element ->
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/**
 * Contacts as the JSON Postgres stores: an array of `{name, phone,
 * relationship}`, with `relationship` omitted when there is none (the server
 * accepts a missing key and a null alike).
 */
internal fun formatContacts(contacts: List<EmergencyContact>): String =
    JsonArray(
        contacts.map { contact ->
            buildJsonObject {
                put("name", contact.name)
                put("phone", contact.phone)
                contact.relationship?.let { put("relationship", it) }
            }
        },
    ).toString()

/**
 * Contacts from SQLite. An object without a readable name or phone is skipped
 * as a whole: a nameless contact cannot be dialled or shown.
 */
internal fun parseContacts(json: String?): List<EmergencyContact> {
    val text = json?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val array = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull()
        ?: return emptyList()

    return array.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val name = (item["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return@mapNotNull null
        val phone = (item["phone"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return@mapNotNull null
        val relationship = (item["relationship"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

        EmergencyContact(name = name, relationship = relationship, phone = phone)
    }
}
