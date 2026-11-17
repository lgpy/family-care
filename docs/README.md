# Family Care — Design Documentation

Private family health management app. Design phase: no code yet.

| Document | Contents |
|---|---|
| [vision-and-mvp.md](vision-and-mvp.md) | Product vision, users, MVP scope, roadmap |
| [decisions.md](decisions.md) | Decision log (D1…), with reasoning and open questions |
| [architecture.md](architecture.md) | System components, stack, modular structure |
| [security-and-privacy.md](security-and-privacy.md) | Threat model, auth, access control, encryption, audit, backups, GDPR |
| [domain/care-circle.md](domain/care-circle.md) | Users, patients, circles, roles, access, reminder routing |
| [domain/medications.md](domain/medications.md) | Medications, regimens, doses, stock, refills |
| [ux/screens.md](ux/screens.md) | Navigation, main screens, daily-use walkthrough |
| [build-plan.md](build-plan.md) | Repository structure, milestones M0–M7, testing, environments, costs |
| [../ops/checklists/setup.md](../ops/checklists/setup.md) | M0: accounts and services setup checklist |

## Conventions

- Decisions are numbered `D<n>` and never renumbered. A changed decision gets a new entry that supersedes the old one.
- Open questions are listed at the end of the decision log until resolved.
