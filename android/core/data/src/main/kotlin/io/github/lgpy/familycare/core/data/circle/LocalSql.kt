package io.github.lgpy.familycare.core.data.circle

/**
 * The local SQL the M2 repositories run against the encrypted database.
 *
 * Every "current circle" query uses the same rule — the signed-in user's
 * **oldest** membership, tie-broken by membership id — so the circle flow, the
 * role flow, the member list and the patient list can never disagree about which
 * circle they describe (the MVP gives a user one circle; this is only the
 * deterministic answer when the server holds more).
 *
 * Parameters are always bound (`?`), never interpolated: names and ids are data.
 */

/** The current circle, joined through the user's oldest membership. */
internal const val SELECT_CIRCLE = """
    SELECT c.id AS id, c.name AS name
    FROM care_circles c
    JOIN memberships m ON m.circle_id = c.id
    WHERE m.user_id = ?
    ORDER BY m.created_at ASC, m.id ASC
    LIMIT 1
"""

/** The signed-in user's role in the current circle. */
internal const val SELECT_MY_ROLE = """
    SELECT role
    FROM memberships
    WHERE user_id = ?
    ORDER BY created_at ASC, id ASC
    LIMIT 1
"""

/** The current circle's id, for the online calls that need it. */
internal const val SELECT_CURRENT_CIRCLE_ID = """
    SELECT circle_id
    FROM memberships
    WHERE user_id = ?
    ORDER BY created_at ASC, id ASC
    LIMIT 1
"""

/**
 * The current circle's members with their profile names: owner first, then by
 * name. `LEFT JOIN` because a co-member's profile row may not have synced yet.
 */
internal const val SELECT_MEMBERS = """
    SELECT m.id AS id, m.user_id AS user_id, m.role AS role, p.display_name AS display_name
    FROM memberships m
    LEFT JOIN profiles p ON p.id = m.user_id
    WHERE m.circle_id = (
        SELECT circle_id FROM memberships WHERE user_id = ? ORDER BY created_at ASC, id ASC LIMIT 1
    )
    ORDER BY CASE m.role WHEN 'owner' THEN 0 WHEN 'caregiver' THEN 1 ELSE 2 END,
             coalesce(p.display_name, ''), m.user_id
"""

/** The current circle's active patients, sorted by name. */
internal const val SELECT_PATIENTS = """
    SELECT id, circle_id, display_name, linked_user_id, reminder_policy, primary_caregiver_id,
           escalation_delay_minutes, missed_window_minutes, agreement_note, archived_at
    FROM patients
    WHERE archived_at IS NULL
      AND circle_id = (
          SELECT circle_id FROM memberships WHERE user_id = ? ORDER BY created_at ASC, id ASC LIMIT 1
      )
    ORDER BY display_name COLLATE NOCASE, id
"""

/** One patient, restricted to the circles the user belongs to. */
internal const val SELECT_PATIENT = """
    SELECT id, circle_id, display_name, linked_user_id, reminder_policy, primary_caregiver_id,
           escalation_delay_minutes, missed_window_minutes, agreement_note, archived_at
    FROM patients
    WHERE id = ?
      AND circle_id IN (SELECT circle_id FROM memberships WHERE user_id = ?)
"""

/*
 * Saving a patient is an existence check plus a plain INSERT or UPDATE in one
 * write transaction. It cannot be one `INSERT ... ON CONFLICT DO UPDATE`:
 * PowerSync tables are SQLite views (with INSTEAD OF triggers), and SQLite
 * refuses to UPSERT a view.
 *
 * `circle_id` is deliberately not updated: a patient never moves between circles
 * (`core.check_patient()` refuses it too). `created_at`/`updated_at` are left
 * alone; the server stamps them and they sync back.
 */

/** Whether the patient row exists locally, whatever its circle. */
internal const val SELECT_PATIENT_PRESENT = """
    SELECT id FROM patients WHERE id = ?
"""

/** Inserts a new patient. */
internal const val INSERT_PATIENT = """
    INSERT INTO patients (
        id, circle_id, display_name, linked_user_id, reminder_policy, primary_caregiver_id,
        escalation_delay_minutes, missed_window_minutes, agreement_note, archived_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

/** Updates an existing patient; the last parameter is the id. */
internal const val UPDATE_PATIENT = """
    UPDATE patients SET
        display_name = ?,
        linked_user_id = ?,
        reminder_policy = ?,
        primary_caregiver_id = ?,
        escalation_delay_minutes = ?,
        missed_window_minutes = ?,
        agreement_note = ?,
        archived_at = ?
    WHERE id = ?
"""

/** The signed-in user's own profile row. */
internal const val SELECT_MY_PROFILE = """
    SELECT id, display_name, preferred_language
    FROM profiles
    WHERE id = ?
"""

/** Whether the profile row exists locally, without reading its values. */
internal const val SELECT_PROFILE_PRESENT = """
    SELECT id FROM profiles WHERE id = ?
"""

/** The only writable profile column the M2 app changes. */
internal const val UPDATE_PROFILE_DISPLAY_NAME = """
    UPDATE profiles SET display_name = ? WHERE id = ?
"""
