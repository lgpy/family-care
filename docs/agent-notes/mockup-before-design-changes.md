---
name: mockup-before-design-changes
description: Mock up UI/design changes for approval before editing app code
type: feedback
---

Before changing any screen's design in family-care, make a mockup first (HTML, in the style of docs/ux/mockups/) and get the user's approval; only then touch Compose code.

**Why:** On 2026-11-20 I redesigned the Today screen (low-stock summary row, folded "all given" groups, compact done slots, outlined later buttons) straight in code; the user said "you should mock things up before changing design".

**How to apply:** For any visual/layout change (even "this page is a mess" style requests), produce a before/after mockup and wait for sign-off. Pure bug fixes (e.g. data rules like untracked stock counted as low) can still go straight to code, but say which part is a design call.
