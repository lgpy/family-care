package io.github.lgpy.familycare.feature.medications.data

/**
 * The local SQL the medications repositories run against the encrypted
 * database.
 *
 * Everything here is the module's own tables (`medications`, `regimens`,
 * `dose_log`, `stock_movements`) plus read-only joins onto the core's `patients`
 * and `memberships` — never a write to a core table, and never a query another
 * feature's tables (D5, architecture "Rules": a module owns its tables).
 *
 * Parameters are always bound (`?`), never interpolated: names and ids are data.
 *
 * ### The "current circle" rule
 * Every circle-scoped query repeats the core's rule — the signed-in user's
 * oldest membership, tie-broken by id — so a feature screen can never disagree
 * with the core about which circle is current.
 *
 * ### Why writes are plain INSERT/UPDATE
 * PowerSync tables are SQLite **views** with `INSTEAD OF` triggers, and SQLite
 * refuses `INSERT ... ON CONFLICT DO UPDATE` on a view. Saves are therefore an
 * existence check plus a plain INSERT or UPDATE inside one write transaction,
 * which is also what makes a multi-row change (medication + first regimen +
 * opening stock count, an ended version + a new one) one queued transaction.
 *
 * `created_at`/`updated_at`/`created_by` are never written locally: the server
 * stamps them and they arrive through sync.
 */

/** The shared projection: one row per medication *and* regimen version. */
private const val MEDICATION_SELECT = """
    SELECT m.id AS id,
           m.patient_id AS patient_id,
           m.name AS name,
           m.active_ingredient AS active_ingredient,
           m.strength AS strength,
           m.form AS form,
           m.stock_unit AS stock_unit,
           m.instructions AS instructions,
           m.requires_prescription AS requires_prescription,
           m.prescription_valid_until AS prescription_valid_until,
           m.pill_shape AS pill_shape,
           m.pill_color AS pill_color,
           m.pill_color2 AS pill_color2,
           m.pill_score AS pill_score,
           m.pill_size AS pill_size,
           m.low_stock_threshold_days AS low_stock_threshold_days,
           m.reminder_policy AS reminder_policy,
           m.primary_caregiver_id AS primary_caregiver_id,
           m.stopped_at AS stopped_at,
           p.display_name AS patient_display_name,
           p.escalation_delay_minutes AS escalation_delay_minutes,
           p.missed_window_minutes AS missed_window_minutes,
           r.id AS regimen_id,
           r.medication_id AS regimen_medication_id,
           r.schedule_type AS schedule_type,
           r.weekdays AS weekdays,
           r.interval_days AS interval_days,
           r.start_date AS start_date,
           r.end_date AS end_date,
           r.time_zone AS time_zone,
           r.times AS times
    FROM medications m
    JOIN patients p ON p.id = m.patient_id
    LEFT JOIN regimens r ON r.medication_id = m.id
"""

/** One patient's medications with their regimen history, by name. */
internal const val SELECT_PATIENT_MEDICATIONS = MEDICATION_SELECT + """
    WHERE m.patient_id = ?
      AND p.circle_id IN (SELECT circle_id FROM memberships WHERE user_id = ?)
    ORDER BY m.name COLLATE NOCASE, m.id, r.start_date, r.id
"""

/**
 * Every active medication of the current circle: not stopped, patient not
 * archived. Used by the medication list and by the doses-today query.
 */
internal const val SELECT_ACTIVE_MEDICATIONS = MEDICATION_SELECT + """
    WHERE m.stopped_at IS NULL
      AND p.archived_at IS NULL
      AND p.circle_id = (
          SELECT circle_id FROM memberships WHERE user_id = ? ORDER BY created_at ASC, id ASC LIMIT 1
      )
    ORDER BY m.name COLLATE NOCASE, m.id, r.start_date, r.id
"""

/** One medication of the current circle, with its regimen history. */
internal const val SELECT_MEDICATION = MEDICATION_SELECT + """
    WHERE m.id = ?
      AND p.circle_id IN (SELECT circle_id FROM memberships WHERE user_id = ?)
"""

/** Whether a medication row exists locally, whatever its circle. */
internal const val SELECT_MEDICATION_PRESENT = """
    SELECT id FROM medications WHERE id = ?
"""

/** The circle and patient of a medication, for the write permission checks. */
internal const val SELECT_MEDICATION_OWNER = """
    SELECT p.circle_id AS circle_id, p.id AS patient_id
    FROM medications m
    JOIN patients p ON p.id = m.patient_id
    WHERE m.id = ?
"""

/** Whether a patient belongs to [given circle]. */
internal const val SELECT_PATIENT_IN_CIRCLE = """
    SELECT id FROM patients WHERE id = ? AND circle_id = ?
"""

/** Whether a medication belongs to [given circle]. */
internal const val SELECT_MEDICATION_IN_CIRCLE = """
    SELECT m.id FROM medications m
    JOIN patients p ON p.id = m.patient_id
    WHERE m.id = ? AND p.circle_id = ?
"""

/** The medication a regimen version belongs to, with its patient and circle. */
internal const val SELECT_REGIMEN_OWNER = """
    SELECT r.medication_id AS medication_id, p.circle_id AS circle_id, p.id AS patient_id
    FROM regimens r
    JOIN medications m ON m.id = r.medication_id
    JOIN patients p ON p.id = m.patient_id
    WHERE r.id = ?
"""

/** Inserts a medication; the id is generated on the phone. */
internal const val INSERT_MEDICATION = """
    INSERT INTO medications (
        id, patient_id, name, active_ingredient, strength, form, stock_unit, instructions,
        requires_prescription, prescription_valid_until, pill_shape, pill_color, pill_color2,
        pill_score, pill_size, low_stock_threshold_days, reminder_policy,
        primary_caregiver_id, stopped_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

/**
 * Updates the user-editable columns of a medication. `patient_id` is
 * deliberately absent: a medication never moves to another patient (the server
 * trigger refuses it too).
 */
internal const val UPDATE_MEDICATION = """
    UPDATE medications SET
        name = ?,
        active_ingredient = ?,
        strength = ?,
        form = ?,
        stock_unit = ?,
        instructions = ?,
        requires_prescription = ?,
        prescription_valid_until = ?,
        pill_shape = ?,
        pill_color = ?,
        pill_color2 = ?,
        pill_score = ?,
        pill_size = ?,
        low_stock_threshold_days = ?,
        reminder_policy = ?,
        primary_caregiver_id = ?,
        stopped_at = ?
    WHERE id = ?
"""

/** Inserts one regimen version. */
internal const val INSERT_REGIMEN = """
    INSERT INTO regimens (
        id, medication_id, schedule_type, weekdays, interval_days,
        start_date, end_date, time_zone, times
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

/**
 * Ends a regimen version. `end_date` is the only column a regimen may ever
 * change (D9), and the upload policy sends exactly this column.
 */
internal const val UPDATE_REGIMEN_END_DATE = """
    UPDATE regimens SET end_date = ? WHERE id = ?
"""

/** Marks a medication stopped; its history stays. */
internal const val UPDATE_MEDICATION_STOPPED_AT = """
    UPDATE medications SET stopped_at = ? WHERE id = ?
"""

/** Every dose-log entry of a medication, oldest slot first (full history). */
internal const val SELECT_MEDICATION_DOSE_LOG = """
    SELECT id, regimen_id, medication_id, scheduled_local, outcome, skip_reason, note,
           actual_at, actual_quantity, confirmed_by, given_by_user_id, given_by_other,
           given_by_name, corrects_entry_id
    FROM dose_log
    WHERE medication_id = ?
    ORDER BY scheduled_local, actual_at, id
"""

/**
 * The dose-log projection for a set of regimens within a date window.
 *
 * The window compares `substr(scheduled_local, 1, 10)` — the `YYYY-MM-DD` prefix
 * — because the column is text and a local write and a synced row must compare
 * the same way whatever separator they carry.
 */
internal const val SELECT_DOSE_LOG_PREFIX = """
    SELECT id, regimen_id, medication_id, scheduled_local, outcome, skip_reason, note,
           actual_at, actual_quantity, confirmed_by, given_by_user_id, given_by_other,
           given_by_name, corrects_entry_id
    FROM dose_log
    WHERE regimen_id IN
"""

/** Orders a dose-log window deterministically; the repository sorts in Kotlin too. */
internal const val DOSE_LOG_WINDOW_SUFFIX = """
      AND substr(scheduled_local, 1, 10) BETWEEN ? AND ?
    ORDER BY scheduled_local, actual_at, id
"""

/** Inserts one append-only dose-log entry (D6). */
internal const val INSERT_DOSE_LOG = """
    INSERT INTO dose_log (
        id, regimen_id, medication_id, scheduled_local, outcome, skip_reason, note,
        actual_at, actual_quantity, confirmed_by, given_by_user_id, given_by_other,
        given_by_name, corrects_entry_id
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

/** Every stock movement of one medication, oldest first (the repository re-sorts). */
internal const val SELECT_MEDICATION_STOCK_MOVEMENTS = """
    SELECT id, medication_id, kind, quantity, reason, occurred_at
    FROM stock_movements
    WHERE medication_id = ?
    ORDER BY occurred_at, id
"""

/** Inserts one append-only stock movement (D6/D28); `created_*` are the server's. */
internal const val INSERT_STOCK_MOVEMENT = """
    INSERT INTO stock_movements (id, medication_id, kind, quantity, reason, occurred_at)
    VALUES (?, ?, ?, ?, ?, ?)
"""
