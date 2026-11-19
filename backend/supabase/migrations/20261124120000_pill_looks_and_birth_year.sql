-- M8 pill looks and an optional birth year (docs/decisions.md D33,
-- docs/ux/design-system.md "Pill pictures" and "Icons").
--
-- Two additive, optional changes:
--
-- 1. `meds.medications` gains a drawn pill description: shape, one or two
--    palette colours, whether the tablet is scored, and a size. The app draws
--    it (`PillImage` in core/ui); no photo, so no storage, upload, offline
--    image cache or syncing images of a family's medication. Every column is
--    optional or defaulted, so an app version that predates this migration
--    keeps reading and writing the table unchanged.
-- 2. `core.patients.birth_year` is optional and only used to show an age.
--    The full date of birth is deliberately not stored (D33).
--
-- What this migration does *not* need (checked against the M2-M7 patterns):
--
-- - Grants: `authenticated` holds table-level SELECT/INSERT/UPDATE (and, for
--   medications, DELETE) on both tables, not column-level lists, so new columns
--   are covered. There is no column-level grant on either table.
-- - RLS: every policy asks core.can(patient_id, action) / core.can(id, action);
--   a new column changes no row's visibility.
-- - Audit (D14): the generic triggers use to_jsonb(old/new), so whole rows,
--   including these columns, reach core.audit_log with no change.
-- - Archive (D30): core.assert_patient_writable_row() guards medications and
--   core.check_patient_archive() compares whole patient rows, so the new
--   columns are read-only on an archived patient automatically.
-- - Replication: every PowerSync stream is `SELECT *`; new columns sync with
--   their table, so powersync/sync-config.yaml and the drift check are
--   unchanged.

-- --- Pill looks (meds.medications) ------------------------------------------

alter table meds.medications
  add column pill_shape text
    constraint medications_pill_shape_allowed
    check (
      pill_shape is null
      or pill_shape in ('round', 'oval', 'oblong', 'capsule', 'diamond', 'triangle', 'drops')
    ),
  add column pill_color text
    constraint medications_pill_color_allowed
    check (
      pill_color is null
      or pill_color in (
        'white', 'cream', 'yellow', 'orange', 'pink', 'red',
        'blue', 'navy', 'green', 'brown', 'purple', 'grey'
      )
    ),
  add column pill_color2 text
    constraint medications_pill_color2_allowed
    check (
      pill_color2 is null
      or pill_color2 in (
        'white', 'cream', 'yellow', 'orange', 'pink', 'red',
        'blue', 'navy', 'green', 'brown', 'purple', 'grey'
      )
    ),
  add column pill_score boolean not null default false,
  add column pill_size text not null default 'medium'
    constraint medications_pill_size_allowed
    check (pill_size in ('small', 'medium', 'large'));

comment on column meds.medications.pill_shape is
  'Drawn pill outline (D33): round, oval, oblong, capsule, diamond, triangle or drops. Null means not described, and the app shows its neutral pill glyph.';
comment on column meds.medications.pill_color is
  'Main palette token for the drawn pill (D33), e.g. white, cream, pink. Null means not described. Not a CSS colour: the app maps the token to a light/dark value.';
comment on column meds.medications.pill_color2 is
  'Optional second palette token for a two-tone pill (D33). Null is the common single-colour case.';
comment on column meds.medications.pill_score is
  'True when the tablet has a score line, which the drawn pill shows (D33). Defaults to false so an older app writes a valid row.';
comment on column meds.medications.pill_size is
  'Drawn pill size: small, medium or large (D33). Relative only; it is not a measurement. Defaults to medium.';

-- --- Optional birth year (core.patients) -----------------------------------

alter table core.patients
  add column birth_year smallint
    constraint patients_birth_year_range
    check (birth_year is null or birth_year between 1900 and 2100);

comment on column core.patients.birth_year is
  'Year of birth only, for an age display (D33). Optional; the day and month are deliberately not stored. Range 1900..2100.';
