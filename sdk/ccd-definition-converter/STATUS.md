# Retrofit conversion status

Where each case type stands when its hand-written CCD definition is converted to
config-generator Java by `--retrofit`. Regenerate with `bin/regen-review-clones.sh`; the numbers
below are from the branch tip named at the bottom.

Two measures matter, and they pull in opposite directions:

- **Residual** — lines of diff between the definition the converted Java regenerates and the
  hand-written one. This is the fidelity measure: zero means the conversion reproduces the
  definition exactly. Held as a per-lane ratchet in
  [`src/test/resources/roundtrip-baselines`](src/test/resources/roundtrip-baselines); `roundTripTest`
  fails on any increase.
- **Graft** — rows the SDK cannot express in Java, carried as raw JSON in `--passthrough-dir` and
  merged back over the generated definition. A graft *prevents* a residual line by reproducing the
  row verbatim, so a lane can be byte-perfect and still be substantially un-migrated.

A low residual with a high graft therefore means "correct output, much of it not yet Java" — the
tractable state. The reverse means the conversion is losing information.

## Where we are

| Case type | Lane | CaseField IDs | Resolved | Exact | Residual | Graft (callback) | Graft (other) |
|---|---|---:|---:|---:|---:|---:|---:|
| `Benefit` | sscs-common | 590 | 93.6% | 26.9% | 11 | 302 | **0** |
| `Asylum` | ia (generate mode) | — | — | — | **1** | — | — |
| `GrantOfRepresentation` | probate-back-office | 547 | 88.1% | 40.0% | 6 | 318 | 40 |
| `ET_EnglandWales` | et-ccd-callbacks | 1009 | 97.9% | 45.8% | 10 | 221 | 255 |
| `FinancialRemedyContested` | finrem-case-orchestration-service | 957 | 99.5% | 40.9% | 98 | 178 | 26 |
| `CIVIL` | civil-service | 1998 | 94.7% | 63.8% | 89 | 851 | 207 |
| `PRLAPPS` | prl-cos-api | 2319 | 73.3% | 56.9% | 43 | 400 | 257 |
| `CARE_SUPERVISION_EPO` | fpl-ccd-configuration | 1696 | 73.4% | 37.0% | 7 | 222 | **878** |
| | | | | | **265** | **2492** | **1663** |

`Benefit` is the existence proof: a real service's definition, converted with **no** non-callback
graft at all. `Asylum` is map-based and uses generate mode, so it has no retrofitted model and no
graft to measure.

Callback graft is a deliberate carve-out, not a shortfall. The converter emits no callback wiring —
`CallBackURL*` and its retry columns are carried verbatim — so the generated definition is provably
identical on callbacks, and adopting real `MidEvent`/`AboutToSubmit` handlers stays an opt-in,
per-event decision. That column shrinks only as teams migrate handlers, never as converter work.

## What the remaining non-callback graft is

Every row below is a `CaseEventToComplexTypes` member the converter could not derive, as the lane's
own `gap-report.md` states it.

| Lane | Rows | Dominant cause |
|---|---:|---|
| fpl | 878 | **657** — no compilable getter chain to the field (a `@JsonUnwrapped` parent's getter is suppressed); 95 member not found on the bound type |
| prl | 257 | 120 member not found; 57 field not declared as a `CaseField`; 39 duplicate `ListElementCode` with genuinely divergent content; 18 overlay-suffixed |
| et | 255 | **108** overlay-suffixed sibling row targets the same (event, field); 25 overlay-suffixed group; 21 member not found |
| civil | 207 | 49 member not found; 44 overlay-suffixed sibling; 10 field not declared; 9 overlay-suffixed group |
| probate | 40 | 30 no `CaseEvent` declares the event; 10 intermediate segment not a walkable complex type |
| finrem | 26 | 24 member not found — the `caseDocumentConfidentialityWarning0`–`7` labels |
| sscs | 0 | — |

Three causes account for most of it, and they are not the same kind of problem:

- **`@JsonUnwrapped` reachability (fpl, 657).** The definition addresses a member Jackson flattens
  into its parent, and the container's getter is suppressed so no compilable chain reaches it. The
  SDK gained `unwrappedScope(Class)` for exactly this shape (addressing the container by type rather
  than by a getter name that does not exist) and it cleared finrem's 76 equivalent rows. fpl's 657
  have not been re-measured against it — the single largest open question here.
- **Overlay-suffixed siblings (et 133, civil 53).** Two rows for one `(event, field)` differing only
  by which environment fragment declares them. Where the predicate is inert the suffix is now folded
  into base; where it is a real environment switch the refusal stands, because the SDK emits one
  definition per run.
- **Member not found (all lanes, ~309).** A definition member with no model field. Some are
  synthesisable and some are genuine divergences between a team's model and its definition — this
  bucket needs per-lane triage rather than one fix.

## Known-good and known-bad

Residual is a ratchet, so any increase fails CI. Two lanes carry residual worth understanding:

- **finrem, 98.** 63 lines are `FixedLists` ID naming (`CU_fl_citizenUploadDocuments` against the
  enum name the SDK derives), 18 are definition-only complex types with no model class, 16 are
  `CaseTypeTab` `DisplayContextParameter` `#TABLE(...)`, 1 is a trailing space in an event name.
- **civil, 89.** Dominated by `MultiSelectList` inference and divergently-named complex types.

Not measured here: whether each lane's converted output **compiles** and **generates**. finrem is
the only lane verified end to end (`compileJava` and `generateCCDConfig` both clean, 27 sheets /
593 JSON files). The others are measured on emission only, so a lane's numbers say its definition
converts — not that the result builds.

Measured at 675361c7 on branch json-definition-converter.
