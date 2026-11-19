package io.github.lgpy.familycare.feature.appointments.data

/**
 * The local SQL the appointments repository runs against the encrypted
 * database.
 *
 * Everything here is the module's own `appointments` table plus a read-only join
 * onto the core's `patients` and `memberships` — never a write to a core table,
 * and never a query another feature's tables (D5).
 *
 * Parameters are always bound (`?`), never interpolated: ids are data.
 *
 * ### The "current circle" rule
 * Every circle-scoped query repeats the core's rule — the signed-in user's
 * oldest membership, tie-broken by id — or checks membership of the circles the
 * user belongs to, so a feature screen can never disagree with the core about
 * which circle is current.
 *
 * ### Why writes are plain INSERT/UPDATE
 * PowerSync tables are SQLite views with `INSTEAD OF` triggers, and SQLite
 * refuses `INSERT ... ON CONFLICT DO UPDATE` on a view. Saves are therefore an
 * existence check plus a plain INSERT or UPDATE inside one write transaction.
 * `created_*`/`updated_*` are never written locally: the server stamps them.
 */

/** The shared projection: one appointment with its patient's name and circle. */
private const val APPOINTMENT_SELECT = """
    SELECT a.id AS id,
           a.patient_id AS patient_id,
           a.kind AS kind,
           a.title AS title,
           a.starts_local AS starts_local,
           a.time_zone AS time_zone,
           a.duration_minutes AS duration_minutes,
           a.place AS place,
           a.doctor AS doctor,
           a.preparation AS preparation,
           a.notes AS notes,
           a.companion_user_id AS companion_user_id,
           a.follow_up_of AS follow_up_of,
           a.cancelled_at AS cancelled_at,
           a.created_at AS created_at,
           a.updated_at AS updated_at,
           p.display_name AS patient_display_name,
           p.circle_id AS circle_id
    FROM appointments a
    JOIN patients p ON p.id = a.patient_id
"""

/** One patient's appointments, earliest first. */
internal const val SELECT_PATIENT_APPOINTMENTS = APPOINTMENT_SELECT + """
    WHERE a.patient_id = ?
      AND p.circle_id IN (SELECT circle_id FROM memberships WHERE user_id = ?)
    ORDER BY a.starts_local, a.id
"""

/** Every appointment of the circles the signed-in user belongs to, except those
 * of archived patients (D30: archived patients are hidden from the Agenda, the
 * Next 7 days card and the appointment reminders). */
internal const val SELECT_CIRCLE_APPOINTMENTS = APPOINTMENT_SELECT + """
    WHERE p.circle_id IN (SELECT circle_id FROM memberships WHERE user_id = ?)
      AND p.archived_at IS NULL
    ORDER BY a.starts_local, a.id
"""

/** One appointment of one of the signed-in user's circles. */
internal const val SELECT_APPOINTMENT = APPOINTMENT_SELECT + """
    WHERE a.id = ?
      AND p.circle_id IN (SELECT circle_id FROM memberships WHERE user_id = ?)
"""

/** Whether an appointment row exists locally, whatever its circle. */
internal const val SELECT_APPOINTMENT_PRESENT = """
    SELECT id FROM appointments WHERE id = ?
"""

/** The patient and circle of an appointment, for the write permission checks. */
internal const val SELECT_APPOINTMENT_OWNER = """
    SELECT a.patient_id AS patient_id, p.circle_id AS circle_id
    FROM appointments a
    JOIN patients p ON p.id = a.patient_id
    WHERE a.id = ?
"""

/** Whether a patient belongs to a given circle. */
internal const val SELECT_PATIENT_IN_CIRCLE = """
    SELECT id FROM patients WHERE id = ? AND circle_id = ?
"""

/** The role of one user in one circle, or no row when they are not a member. */
internal const val SELECT_MEMBER_ROLE = """
    SELECT role FROM memberships WHERE circle_id = ? AND user_id = ?
"""

/** The patient of a follow-up appointment, to check it belongs to the same one. */
internal const val SELECT_APPOINTMENT_PATIENT = """
    SELECT patient_id FROM appointments WHERE id = ?
"""

/** Inserts an appointment; the id is generated on the phone. */
internal const val INSERT_APPOINTMENT = """
    INSERT INTO appointments (
        id, patient_id, kind, title, starts_local, time_zone, duration_minutes,
        place, doctor, preparation, notes, companion_user_id, follow_up_of
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

/**
 * Updates the user-editable columns of an appointment. `patient_id` is
 * deliberately absent: an appointment never moves to another patient (the
 * server trigger refuses it too).
 */
internal const val UPDATE_APPOINTMENT = """
    UPDATE appointments SET
        kind = ?,
        title = ?,
        starts_local = ?,
        time_zone = ?,
        duration_minutes = ?,
        place = ?,
        doctor = ?,
        preparation = ?,
        notes = ?,
        companion_user_id = ?,
        follow_up_of = ?,
        cancelled_at = ?
    WHERE id = ?
"""

/** Cancels an appointment; the row and its history stay (D29). */
internal const val UPDATE_APPOINTMENT_CANCELLED_AT = """
    UPDATE appointments SET cancelled_at = ? WHERE id = ?
"""

/** Deletes an appointment (owner only, enforced by the server's RLS too). */
internal const val DELETE_APPOINTMENT = """
    DELETE FROM appointments WHERE id = ?
"""
