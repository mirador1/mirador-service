# ADR-0056 — Widget extraction pattern for large Angular components

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: Mirador maintainers
- **Related**:
  [`~/.claude/CLAUDE.md`](../../CLAUDE.md) → "File length hygiene" + "1
  widget / 1 panel = 1 file",
  [ADR-0050](0050-ci-yaml-modularisation-plan.md) (CI YAML same idea
  applied to pipeline files)

## Context

The UI repo accumulated several Angular components past the 700-LOC
mark by 2026-04-22 :

- `dashboard.component` (745 .ts + 505 .html)
- `customers.component` (857 .ts + 494 .html)
- `security.component` (566 .ts + 626 .html)
- `diagnostic.component` (628 .ts)
- `about.component` (652 .ts)
- `chaos.component` / `database.component` (~600 .ts each)

The CLAUDE.md "File length hygiene" rule kicks in :
- ≥ 1000 LOC : plan a split at the next touch
- ≥ 1500 LOC : split NOW

None of the above were over 1500 yet, but they were all approaching
the 1000-LOC plan-split trigger. **2026-04-23** session pushed several
through the extraction process (Phases B-6b, B-7-2b, B-7-4) and a clear
**repeatable pattern** emerged that's worth codifying so future
extractions stay consistent.

## Decision

Adopt the **"1 widget / 1 panel = 1 file"** pattern for any Angular
component that's a *container of independent things* (a dashboard
holding 8 widgets, a quality page holding 10 panels, a settings screen
holding 12 tabs). The container becomes a thin layout shell ;
each widget gets its own `widgets/<name>.component.ts` (+ optional
`.html` if template > 150 LOC).

### Anatomy of an extracted widget

```
src/app/features/<feature>/<page>/
├── <page>.component.ts        # Parent : state + HTTP + signals (slim)
├── <page>.component.html      # Parent template : layout + <app-widget> tags
├── <page>-types.ts            # Shared interfaces between parent + widgets
├── <page>-data.ts             # (optional) static data tables
└── widgets/
    ├── <feature>-<concern>.component.ts
    ├── <feature>-<concern>.component.html  (only if template > 150 LOC)
    └── <feature>-<concern>.component.spec.ts
```

### Naming rule

Child name **preserves the parent's concern** so `grep -rn "Dashboard…"`
lands directly on the relevant widget :

| Parent | Widget name |
|---|---|
| DashboardComponent | DashboardHealthProbesComponent ✅ |
|                    | DashboardArchitectureMapComponent ✅ |
|                    | ~~HealthProbesComponent~~ ❌ (loses parent context) |
| CustomersComponent | CustomerDetailPanelComponent ✅ |
|                    | CustomerCreateFormComponent ✅ |
| SecurityComponent  | SecurityMechanismsTabComponent ✅ |

### Data flow

Widgets are **presentational** by default :

| Direction | Mechanism | When |
|---|---|---|
| Parent → Widget | `input<T>()` signal | All read-only state passed down |
| Widget → Parent | `output<T>()` event emitter | All actions (clicks, form submits) |
| Widget internal | Local `signal()` + `viewChild()` | Form input state, refs to DOM elements |
| Widget services | `inject()` directly | Pure read-only services (DeepLinkService, ActivityService) |

Parent owns :
- HTTP client + retry policy
- State signals shared across widgets
- Top-level navigation / routing

Widget owns :
- Its own form input signals (when self-contained, e.g.
  CustomerCreateForm's `newName`/`newEmail`/`useIdempotencyKey`)
- viewChild refs for zoneless DOM-fallback patterns
- Pure rendering helpers that only serve this widget's template

### Template inline vs separate

| Template size | Choice |
|---|---|
| < 150 LOC | Inline in `template:` (faster to read, no extra file) |
| ≥ 150 LOC | Separate `.html` via `templateUrl:` |

Examples : DashboardHealthProbes (~85 LOC) inline ; DashboardArchitectureMap
(~175 LOC) separate `.html`.

### Style sharing

Widgets reuse the parent's SCSS via `styleUrl: '../<parent>.component.scss'`
to avoid CSS class duplication. Don't add per-widget `.scss` unless the
widget's styles are truly its own (rare for extractions ; common for
brand-new components).

### Output naming gotcha

`@angular-eslint/no-output-native` flags output names that shadow
native DOM events (`cancel`, `confirm`, `close`, `submit`, `select`).
Use **past-tense** instead :

| Native event | Use instead |
|---|---|
| `cancel` | `cancelled` |
| `confirm` | `confirmed` |
| `close` | `closeRequested` |
| `submit` | `submitRequested` |

Discovered 2026-04-23 in pipeline #409 ; ConfirmModal had to be renamed
mid-MR.

### Spec file expectations

Every extracted widget gets a `.spec.ts` :
- Smoke : class export + `TestBed.createComponent` clean instantiation
- Helpers : pure function tests for any non-trivial method moved into
  the widget
- Defaults : verify `input()` defaults match the documented shape
- Outputs : behavioural tests for emit conditions when feasible
  (skip when viewChild rendering is needed — covered at parent
  integration level instead)

## Consequences

### Positive

- **Navigability**. Each widget < 175 LOC, fully self-contained. New
  contributors can read one widget end-to-end without holding 700+ LOC
  of parent context in head.
- **Test isolation**. Pure presentational widgets are trivial to test
  (no HTTP, no router, just inputs/outputs). 11 new tests added across
  6 widgets in the 2026-04-23 wave for ~250 LOC of spec.
- **Diff readability**. A widget change shows up in 1 file ; a parent
  change in 1 file. Reviewers don't have to scroll past 600 LOC to find
  the actual diff.
- **Reuse**. Generic widgets like `ConfirmModal` (extracted from
  customers) can be reused across the app — diagnostic destructive
  ops, settings reset, OAuth disconnect — without re-implementing the
  modal pattern each time.
- **Faster TypeScript checking**. Smaller files = faster incremental
  type-checks in `ng build`.

### Negative

- **More files**. Each extracted widget adds 2-3 files (.ts + .spec
  + optional .html). The `widgets/` directory grows quickly for
  multi-tab pages.
- **Input/output ceremony** for tightly-coupled blocks. The
  DashboardArchitectureMap widget needs 6 signal inputs + 2 outputs
  because the parent owns the topology state pipeline. That's verbose
  but explicit beats hidden coupling.
- **Inline-template reading flow**. For widgets ≥ 150 LOC the template
  moves to a separate `.html`. Inline templates are faster to grok
  in-place ; the trade-off is justified once template size exceeds the
  inline budget (~150 LOC).

### Neutral

- **No global config / lint rule enforces this** — it's a convention
  applied at refactor time. The CLAUDE.md "File length hygiene" rule
  triggers extractions ; this ADR is the "how to do them once
  triggered".
- **Doesn't affect the build**. Standalone components are imported via
  the parent's `imports: []` array, no module needed.

## Alternatives considered

### Alternative A — Keep components monolithic, just split the .html

Tried 2026-04-22 on `dashboard.component` (Phase B-6) : extracted
types + topology data + Sass partials but kept all logic + template
inline. Result : .ts dropped 1022 → 745 LOC, .html unchanged at 505
LOC. **Verdict** : helps the .ts a bit, but the parent template stays
unscrollable. Per-widget extraction is the bigger win (.html drops
65 % at the cost of 4 widgets).

### Alternative B — Extract widgets as service-injected child components

Use Angular DI to share state via a service rather than @Input. **Pro** :
no input ceremony. **Con** : breaks the "component as DAG, services as
shared utilities" mental model ; widgets become hard to reason about
in isolation. Rejected.

### Alternative C — Use NgRx selectors / Signal Store for cross-widget state

Bigger refactor : adopt a state management library (NgRx Signal Store,
Akita, etc) so widgets read derived state from a global store rather
than via inputs. **Pro** : powerful for complex apps. **Con** : adds a
dependency + learning curve ; current signal+input model already does
the job for portfolio-scale features. Revisit if the app grows past
20-30 components AND state-sharing pain points appear.

### Alternative D — Do nothing, accept large files

What we did until 2026-04-22 — components grew to 700-857 LOC. Worked
fine functionally but slowed reviews + onboarding + any new feature
that touched the file. Rejected by the CLAUDE.md "File length hygiene"
rule.

## References

- [`~/.claude/CLAUDE.md`](../../CLAUDE.md) → "File length hygiene"
  rule (≥ 1000 plan, ≥ 1500 split-NOW) + "1 widget / 1 panel = 1 file"
  examples
- Dashboard B-6b extraction (UI MR !108, 2026-04-23) : 3 widgets
  extracted, parent .html dropped 505 → 179 LOC
- Customer B-7-2b extraction (UI MR !109, 2026-04-23) : 2 widgets
  + ConfirmModal generic widget, parent .html dropped 494 → 252 LOC
- Security B-7-4 extraction (UI MR !110, 2026-04-23) : Mechanisms tab
  widget + static data file, parent .ts dropped 566 → 426 LOC
- 6 spec files added in the same wave, all using TestBed.createComponent
  + signal-input verification pattern
