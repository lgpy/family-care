-- Pentagon pill shape (docs/decisions.md D33, docs/ux/design-system.md
-- "Pill pictures").
--
-- Five-sided tablets are common and none of the original shapes looked like
-- them. The shape is a text token, so this only widens the check; an app that
-- predates it reads 'pentagon' as an unknown shape and draws the neutral pill
-- glyph, never a crash.

alter table meds.medications
  drop constraint medications_pill_shape_allowed,
  add constraint medications_pill_shape_allowed
    check (
      pill_shape is null
      or pill_shape in (
        'round', 'oval', 'oblong', 'capsule', 'diamond', 'triangle', 'pentagon', 'drops'
      )
    );

comment on column meds.medications.pill_shape is
  'Drawn pill outline (D33): round, oval, oblong, capsule, diamond, triangle, pentagon or drops. Null means not described, and the app shows its neutral pill glyph.';
