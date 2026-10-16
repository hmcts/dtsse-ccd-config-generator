# Retrofit conversion status

Where each case type stands when its hand-written CCD definition is converted to
config-generator Java by `--retrofit`. Re-measure with `bin/retrofit-verify --stop-after convert` over
the lane table in `bin/regen-review-clones.sh`; the numbers below are from the commit named at the
bottom.

Two measures matter, and they pull in opposite directions:

- **Residual** — lines of diff between the definition the converted Java regenerates and the
  hand-written one. This is the fidelity measure: zero means the conversion reproduces the
  definition exactly. The column below is the generate-mode round-trip of each definition, held as a
  per-fixture ratchet in
  [`src/test/resources/roundtrip-baselines`](src/test/resources/roundtrip-baselines); `roundTripTest`
  fails on any new or vanished line. The retrofit-lane residual (the patched team model built in its
  own repo) is measured by `bin/retrofit-verify` and recorded in
  [`docs/json-conversion-fidelity.md`](../../docs/json-conversion-fidelity.md#retrofit-lane-residual).
- **Graft** — rows the SDK cannot express in Java, carried as raw JSON in `--passthrough-dir` and
  merged back over the generated definition: verbatim rows, plus column grafts that add a column to a
  generated row. A graft *prevents* a residual line by reproducing the row, so a lane can be
  byte-perfect and still be substantially un-migrated.

A low residual with a high graft therefore means "correct output, much of it not yet Java" — the
tractable state. The reverse means the conversion is losing information.

## Where we are

| Case type | Lane | CaseField IDs | Resolved | Exact | Residual | Graft |
|---|---|---:|---:|---:|---:|---:|
| `Benefit` | sscs-common | 590 | 93.6% | 26.9% | 11 | **0** |
| `Asylum` | ia-case-api (generate mode) | 2408 | — | — | **1** | **0** |
| `GrantOfRepresentation` | probate-back-office | 547 | 88.1% | 40.0% | 6 | 40 |
| `ET_EnglandWales` | et-ccd-callbacks | 1009 | 97.9% | 45.8% | 10 | 161 |
| `FinancialRemedyContested` | finrem-case-orchestration-service | 957 | 99.5% | 40.9% | 98 | 2 |
| `CIVIL` | civil-service | 1998 | 94.7% | 63.8% | 89 | 148 |
| `PRLAPPS` | prl-cos-api | 2319 | 73.3% | 56.9% | 43 | **210** |
| `CARE_SUPERVISION_EPO` | fpl-ccd-configuration | 1696 | 73.4% | 37.0% | 7 | 70 |
| | | | | | **265** | **631** |

`Benefit` and `Asylum` are the existence proof: real services' definitions, converted with **no**
graft at all.

`Asylum` is the one case type that does not retrofit: `AsylumCase extends HashMap<String, Object>`,
so there are no typed members to annotate. Generate mode builds a typed model from the definition
instead, and adds it alongside the map rather than patching it — so the Resolved and Exact columns,
which measure matches against an existing model, do not apply. It converts with no passthrough
files.

Callbacks are not grafted. Each is emitted as a URL callback (`.aboutToSubmitCallback(url,
retries...)`, `fields.page(id, url, retries...)`, …) that the generator writes verbatim, so the
generated definition keeps the service's own endpoints, `${CCD_DEF_*}` placeholders included.

## What the remaining graft is

Causes as each lane's own `gap-report.md` states them. Every graft except civil's 24
`AuthorisationComplexType` rows is a `CaseEventToComplexTypes` row: either a verbatim member row the
converter could not derive, or an exotic-tail column (`SecurityClassification`, `FieldType`, …)
grafted onto a derived row.

| Lane | Rows | Dominant cause |
|---|---:|---|
| prl | 210 | 208 verbatim: 94 member not found on the bound type; 57 field not declared as a `CaseField`; 39 duplicate `ListElementCode` with divergent content; 18 overlay-suffixed group. 2 tail grafts |
| et | 161 | **108** overlay-suffixed sibling row targets the same (event, field); 25 overlay-suffixed group; 21 member not found; 5 no `CaseEvent` declares the event; 2 non-canonical key value (whitespace/case) |
| civil | 148 | 104 verbatim: 44 overlay-suffixed sibling; 30 member not found; 10 field not declared; 9 overlay-suffixed group; 5 no `CaseEvent`; 4 duplicate `ListElementCode`; 2 non-canonical key value. 24 `AuthorisationComplexType` rows from the `prod` fragment (`grantComplexType` has no environment gate). 20 tail grafts |
| fpl | 70 | **51** tail grafts; 19 verbatim: 17 member not found, 2 non-canonical key value |
| probate | 40 | 30 no `CaseEvent` declares the event; 10 intermediate segment not a walkable complex type |
| finrem | 2 | 2 tail grafts (`FieldType` on `FR_manageScannedDocs`/`manageScannedDocumentCollection`) |
| sscs | 0 | — |

Three causes account for most of it, and they are not the same kind of problem:

- **Overlay-suffixed siblings and groups (et 133, civil 53, prl 18).** Rows for one `(event, field)`
  that differ only by which environment fragment declares them. Where the predicate is inert the
  suffix is folded into base; where it is a real environment switch the converter refuses, because
  the SDK emits one definition per run.
- **Member not found (prl 94, civil 30, et 21, fpl 17).** A definition member with no model field.
  Some are synthesisable and some are genuine divergences between a team's model and its
  definition — this bucket needs per-lane triage rather than one fix.
- **Undeclared fields and events (prl 57, probate 30, civil 15, et 5).** Rows naming a field no
  `CaseField` row declares, or an event no `CaseEvent` row declares: dead rows, or rows belonging to a
  sibling case type.

No lane has a row falling back because the model exposes no compilable getter chain (a
`@JsonUnwrapped` parent whose getter is suppressed): the emitted scope opens such a hop by type with
the SDK's `unwrappedScope(Class)`.

## Known-good and known-bad

Residual is a ratchet, so any change fails CI. Two lanes carry residual worth understanding:

- **finrem, 98.** 63 lines are `FixedLists` rows of `CU_fl_citizenUploadDocuments` with no match in
  the regenerated definition, 18 are `ComplexTypes` rows with no match (16 on the `CU_ct_`
  citizen-upload types, 2 on `FR_accessCodeEntry`), 16 are `CaseTypeTab` `DisplayContextParameter`
  `#TABLE(...)`, 1 is a trailing space in an event name.
- **civil, 89.** Led by `ComplexTypes` column tails (36: `Label` 11, `CaseFieldID` 10, `CaseEventID`
  8), then `CaseField` 13, `CaseEventToFields` 12, `CaseTypeTab` 9 and `FixedLists` 8.

Not measured here: whether each lane's converted output **compiles** and **generates**. Two lanes
are verified end to end, both with `compileJava` and `generateCCDConfig` clean: finrem (27 sheets /
593 JSON files) and Asylum (27 sheets / 625 JSON files, against the published
`json-definition-converter-160.1-fb82ae3e` with no local overrides). The others are measured on
emission only, so a lane's numbers say its definition converts — not that the result builds.

Measured 2026-10-13 at 87911088 on branch json-definition-converter: retrofit lanes with
`bin/retrofit-verify --stop-after convert` and the `bin/regen-review-clones.sh` lane table (overlays,
`--env`, type hints), counting rows in each lane's `resources/ccd-passthrough`; Asylum in generate mode
on ia-ccd-definitions 8124b93; Residual from the checked-in baselines.
