# ET CCD JSON-to-Java architecture and delivery plan

## Decision summary

The ET definition should be built as eight case-type aggregates which share typed definition modules. Configuration
sharing will use composition, not a hierarchy of `CCDConfig` base classes and regional subclasses.

The existing ET data classes remain the persisted wire models during this migration. They will not be copied into
definition-only DTOs, split into empty regional subclasses or comprehensively refactored just to make generation easier.
Where a shared data class contains a union of England/Wales and Scotland fields, a typed schema profile will select the
correct regional projection during generation.

The generated output has three explicit dimensions:

```text
definition profile (cftlib or prod)
  -> jurisdiction bundle (admin, England/Wales or Scotland)
    -> case-type aggregate (single, listing, multiple, admin or pre-hearing deposit)
```

Case-type-specific rows remain owned by a case-type aggregate. Workbook-global rows such as complex types and fixed
lists are coalesced at the jurisdiction-bundle boundary. Exact duplicates are written once; conflicting definitions with
the same CCD identity fail generation.

Callback handlers and runtime routing are outside this migration. Java must still express the exact callback URL
metadata needed in generated JSON, using a typed generation API which is separate from registering a Java callback
handler.

## What the repository contains

The golden `cftlib` workbook contains the following case-type-specific rows. Workbook-global rows are shown separately
because they are shared dependencies rather than belonging safely to one event or case type.

| Jurisdiction | Case type | Existing root model | Case-type rows |
| --- | --- | --- | ---: |
| admin | `ET_Admin` | `AdminData` | 117 |
| admin | `Pre_Hearing_Deposit` | `PreHearingDepositData` | 152 |
| England/Wales | `ET_EnglandWales` | `CaseData` | 8,462 |
| England/Wales | `ET_EnglandWales_Listings` | `ListingData` | 227 |
| England/Wales | `ET_EnglandWales_Multiple` | `MultipleData` | 1,325 |
| Scotland | `ET_Scotland` | `CaseData` | 8,093 |
| Scotland | `ET_Scotland_Listings` | `ListingData` | 246 |
| Scotland | `ET_Scotland_Multiple` | `MultipleData` | 1,318 |

The admin workbook has 36 global rows. England/Wales has 3,592 and Scotland has 3,504, mostly complex types,
event-to-complex mappings and fixed-list elements. The convergence baseline counts both `cftlib` and `prod`, so these
figures are descriptive rather than a second progress metric.

### Regional commonality and differences

After replacing only the regional case-type prefix with a neutral value, 9,073 England/Wales rows have an exact
Scotland counterpart in the `cftlib` workbooks. This conservative comparison does not alias the two regional fixed-list
sheet names. Important sheet-level results are:

| Sheet | England/Wales | Scotland | Exact shared after case-type normalisation | E/W-only | Scotland-only |
| --- | ---: | ---: | ---: | ---: | ---: |
| `CaseField` | 1,185 | 1,176 | 1,114 | 71 | 62 |
| `CaseEvent` | 180 | 176 | 116 | 64 | 60 |
| `CaseEventToFields` | 1,197 | 1,207 | 1,016 | 181 | 191 |
| `ComplexTypes` | 1,200 | 1,232 | 1,065 | 135 | 167 |
| `EventToComplexTypes` | 1,004 | 1,014 | 822 | 182 | 192 |
| `AuthorisationCaseField` | 5,752 | 5,406 | 3,683 | 2,069 | 1,723 |
| `AuthorisationCaseEvent` | 706 | 704 | 510 | 196 | 194 |

This is strong enough to justify shared modules, but the deltas are too substantial to hide behind a boolean or a bag
of optional values. A common event is one module applied to both case-type groups. A genuinely regional event or policy
is a separate regional module. A partially shared feature is an ordinary Java function with a small, domain-named
regional policy, not an `EventSpec` representation of spreadsheet columns.

The environment dimension is also semantic. Compared with `prod`, `cftlib` has 968 additional England/Wales rows and
949 additional Scotland rows. A single generated directory cannot eventually match both outputs. Definition generation
will therefore run once per explicit profile, with non-production-only modules enabled only in the `cftlib` profile.

### Current model constraints

`CaseData` is a 1,930-line union model used for both regions. It extends `Et1CaseData`, which extends `BaseCaseData`.
`MultipleData` also extends `BaseCaseData`, but currently redeclares 34 of the inherited referral fields. `ListingData`
contains both England/Wales and Scotland venue fields. These types are successful runtime payload models, but their Java
inheritance does not describe the CCD schema boundaries precisely.

The migration must not amplify those problems:

- do not create `EnglandWalesCaseData extends CaseData` and `ScotlandCaseData extends CaseData`; inherited union fields
  would still be generated and the subclasses would exist only to satisfy a tool;
- do not copy fields into parallel migration models;
- do not refactor the existing inheritance chain while converting an event; and
- fail on duplicate CCD field identities with conflicting metadata instead of relying on merge order.

Composition remains the preferred direction for new domain concepts when the JSON wire shape is a real nested CCD
complex type. `@JsonUnwrapped` may compose a deliberately flat wire model, but a broad conversion of the existing root
classes is separate domain-refactoring work with its own serialisation and callback regression tests.

## Definition architecture

### Case-type aggregates

Each case type has one foundation component which owns:

- the exact case-type metadata;
- jurisdiction metadata;
- its state family;
- the roles applicable to that case type;
- its schema profile; and
- its exact `groupingKey()`.

All other components add a cohesive feature to that aggregate. The intended package shape under the generation-only
source set is:

```text
ccd/migration/config/
  common/                 shared identifiers, roles and access policies
  admin/                  ET_Admin aggregate
  prehearingdeposit/      Pre_Hearing_Deposit aggregate
  singles/common/         modules identical in both regions
  singles/englandwales/   explicit E/W deltas
  singles/scotland/       explicit Scotland deltas
  listings/common/        shared listing modules
  listings/englandwales/
  listings/scotland/
  multiples/common/       shared multiple modules
  multiples/englandwales/
  multiples/scotland/
```

This is composition in two senses. A case type is the sum of independent modules resolved into one builder, and a
regional definition is the sum of common modules plus explicit regional modules. Components do not inherit builder
behaviour from an ET base class.

### Sharing one module across groups

The current SDK requires one `groupingKey()` per `CCDConfig`, which would force a pair of empty forwarding classes for
every definition shared by England/Wales and Scotland. That is line growth caused by the API rather than by the domain.

Add a backwards-compatible SDK capability for a component to declare multiple grouping keys. The generator will apply
that component independently to each named group. A multi-group component must be region-neutral; anything which needs
to know the active region belongs in a regional component. Existing single-key implementations remain unchanged.

### Regional schema profiles

`groupingKey()` separates generated case types but does not alter reflection over the shared root class. Introduce a
typed schema-profile mechanism in the SDK:

- a foundation selects marker types such as `EnglandWalesDefinition` or `ScotlandDefinition`;
- `@CCD` metadata may include or exclude a field for typed marker profiles;
- the same filtering applies while finding reachable complex types and while generating field authorisation;
- default behaviour with no profile remains unchanged; and
- an included field which depends on an excluded or unresolved complex type fails validation.

Marker classes avoid raw case-type strings in the model and allow the small regional delta to be annotated without
enumerating the thousand shared fields. This is a schema projection of the existing wire model, not a second data model.

### States and roles

Use one state enum per genuinely shared state machine:

- singles share `CaseState`;
- listings share `ListingCaseState`;
- multiples share `MultipleCaseState`;
- admin and pre-hearing deposit use separate state metadata because their `Open` state has a different title display.

Do not introduce a state inheritance hierarchy. Enum values are external CCD identifiers.

Use one ET role enum containing the stable external role identifiers, with named access-control policies for repeated
permission sets. The current SDK emits every non-case role in the enum for every case type, but the golden definitions
have case-type-specific subsets. Add an explicit applicable-role set to the case-type foundation; generators for case
roles and authorisation must honour it. The default remains all enum constants for compatibility.

This avoids six near-identical role enums while keeping the regional choices explicit. For example, the England/Wales
single aggregate selects `caseworker-employment-englandwales`, while the Scotland aggregate selects
`caseworker-employment-scotland`; the common API, legal representative and access-profile roles are selected by both.

### Workbook-global definitions

`ComplexTypes`, fixed lists and some event-to-complex rows have no case-type identity in CCD. They must be generated from
the dependencies of all case types in the jurisdiction, then aggregated once.

The jurisdiction aggregator will:

1. retain case-type-specific rows independently;
2. coalesce exact global rows with the same sheet identity;
3. fail with both owners named when global rows conflict;
4. preserve duplicate rows only where the golden workbook itself requires duplicate occurrences; and
5. validate all fixed-list and complex-type references after aggregation.

The progress comparator remains downstream of this aggregation so its number describes the JSON which could actually
form the ET workbook, not per-case generator fragments which would duplicate global definitions.

### Fixed lists

ET has 220 England/Wales and 219 Scotland fixed-list IDs in `cftlib`, containing 1,387 and 1,257 elements respectively.
These are domain reference data, but representing them as arbitrary row maps would merely move the spreadsheet into
Java.

Prefer enums with explicit codes and labels. Add a typed SDK registration API so an enum can supply a fixed list even
when the runtime model field remains a `String`; changing hundreds of callback-facing fields from strings to enums is
not part of a definition migration. Exact list definitions shared by several case types are registered once and
coalesced by the jurisdiction aggregator. Review Java line growth for each list group, but do not replace typed values
with a generic `(listId, code, label)` catalogue solely to reduce the line count.

### Definition profiles

Generate `cftlib` and `prod` Java directories separately. The profile is an immutable value supplied to the generation
context, not an ambient collection of `ET_ENV` conditionals scattered through event classes. A component may declare
that it belongs to all profiles, production, or non-production. The XLSX harness compares each golden workbook with the
matching generated profile.

Environment placeholders in callback and printable-document URLs remain strings until ET's existing substitution step.
The Java definition therefore preserves `${ET_COS_URL}` and `${CCD_DEF_URL}` rather than resolving developer-specific
hosts during generation.

### Legacy coexistence and ownership

Convergence and deployable packaging are related but separate checks. The Java-only workbook measures how much of the
golden definition Java can reproduce. A build-side ownership manifest will identify the converted feature and its row
identities, remove those rows from the processed legacy input and overlay the matching Java rows. The merged workbook
must remain identical to golden at every commit.

The manifest names case types and domain features, with sheet identities expanded by the build tooling. It must not
repeat field labels, callback columns or permissions as another definition format. Ownership cannot be transferred
until Java-only parity is exact for the slice. A duplicate owner, a missing owned Java row or an unowned Java row fails
the merge.

## SDK changes are driven by semantic gaps

The following capabilities are expected. They should be delivered only when the next slice needs them, with focused SDK
golden tests and backwards-compatible defaults:

1. typed case-type and jurisdiction metadata, including `LiveFrom`, printable-document URL, deletion, retry timeout and
   the jurisdiction `Shuttered` value;
2. the ability to omit the automatic `caseHistory` field for a case type which does not define it;
3. explicit external callback URL metadata, mutually exclusive with a registered Java callback handler;
4. multiple grouping keys for a region-neutral shared module;
5. typed applicable-role selection;
6. typed schema profiles applied consistently to root fields, complex reachability and authorisation;
7. fixed-list registration independent of a Java field's runtime type; and
8. resolution of ET's concrete collection item wrappers to their CCD value type.

Do not add arbitrary output-column setters. Case-type metadata, a callback endpoint, a schema profile and a fixed list
are reusable CCD concepts; `putColumn(String, Object)` is not.

## Dependency order inside a case type

CCD configuration is cyclic at runtime, but definition construction has a tractable dependency order:

```text
case type + jurisdiction + profile
  -> applicable roles + states + schema projection
    -> case fields + complex types + fixed lists
      -> events + pages + nested event fields
        -> event/state/field/complex authorisation
          -> tabs + workbasket + search + categories + access profiles
            -> jurisdiction aggregation + reference validation
              -> canonical XLSX comparison
```

An implementation slice is enabled in the generator only when all automatically emitted upstream rows are ready. In
particular, enabling the singles foundation would immediately expose roughly one thousand case fields per region; it
must not be used as a way to migrate just one unrelated event before its schema projection and field metadata are sound.

## Delivery sequence

### Slice 1: complete `Pre_Hearing_Deposit`

Use the smallest independent aggregate to prove the architecture without regional or environment variation. This case
type has one state, one event and no fixed lists. The slice owns:

- its case type and the shared `EMPLOYMENT` jurisdiction row;
- all 35 case fields and the three `ImportFile` complex elements;
- `Open` state metadata;
- the `initiateCase` event and its 33 fields;
- 34 tab rows;
- search and workbasket rows;
- case-type, field, state and event authorisation; and
- the exact external about-to-submit URL as generation metadata.

The golden definition has 152 case-type-specific rows plus one jurisdiction row and three `ImportFile` rows. Both
tracked profiles are identical for this aggregate, so exact parity should remove 312 baseline differences and move the
headline metric from `0.00%` to approximately `0.60%`. The slice is not accepted if it adds changed or unexpected rows.

The foundation must support the case type's `28/09/2023` live date, retry timeout and absence of `caseHistory`. Runtime
callback wiring is explicitly not part of the slice.

### Slice 2: complete `ET_Admin`

This proves two case types in one jurisdiction and coalescing of the shared `ImportFile` complex type. It also introduces
the first fixed lists and event-to-complex rows while keeping the regional dimension out of scope.

### Slice 3: listings as a regional pair

Listings are the smallest shared-model pair: 227 England/Wales rows and 246 Scotland rows before global dependencies.
Convert their complete schemas and shared events together, with explicit venue/report deltas. This proves multiple
grouping keys, schema profiles and regional aggregation before attempting the larger case families.

### Slice 4: multiples as a regional pair

Reconcile the duplicated `BaseCaseData` field identities before enabling generation. Convert common multiple features in
paired modules and keep regional office, correspondence and event differences in regional modules.

### Slice 5 onward: singles by business capability

Prepare the full regional field projection before enabling the singles foundations. Then migrate coherent feature
families, normally in this order:

1. base case lifecycle and access;
2. ET1 and ET3 intake;
3. listing and hearing management links;
4. correspondence and document management;
5. referrals and case management;
6. citizen hub, legal representation and notice of change;
7. HMC, work allocation and non-production features; and
8. retention, migration and administrative events.

Within a feature, implement the exact common module first and add named regional deltas beside it. Do not migrate all
rows from one spreadsheet sheet in isolation: event fields, nested complex fields and permissions move with the event.

## Testing and review gates

Every slice must satisfy all of these gates:

1. golden JSON is unchanged;
2. SDK enhancements have focused JSON golden tests and invalid-combination tests;
3. generation-only ET configuration compiles without starting the ET application;
4. `./gradlew :et:etMigrationProgress` passes for `cftlib` and `prod`;
5. the snapshot has no new changed or unexpected rows and records the intended exact-row gain;
6. Java physical-line growth and main-lines-per-completed-difference are reviewed;
7. jurisdiction aggregation has no conflicting global identities or dangling type/list references; and
8. tests follow `docs/testing-strategy.md`.

These gates establish the exact conversion commit. After it is committed, perform a separate generator-fit review of
the committed Java and generated output. Look for repeated policies, awkward spreadsheet-shaped APIs, regional
duplication and production line growth which a narrow typed SDK feature could improve. Record the decision. Any
resulting refactor must be a follow-up commit, retain exact parity and have focused backwards-compatibility and
precedence tests; do not rewrite the original working conversion commit.

The current full `:et:check` also has an unrelated `org.lz4:lz4-java` capability conflict in the cftlib IDE
classpath. The focused migration tasks remain required while that repository-wide dependency issue exists; it must not
be mistaken for a convergence failure.

Callback behaviour tests are not a gate during definition-only conversion because no callback handlers are being moved.
When callback wiring becomes a separate authorised workstream, it must add cftlib/runtime tests before the legacy
routing is changed.

## End state

At 100% convergence, both Java definition profiles generate the complete canonical ET workbooks for all three
jurisdiction bundles. The legacy JSON remains an untouched historical golden until a separate removal decision. The
runtime may continue using its existing callback controllers until callback migration is designed and tested; definition
ownership does not silently alter that routing.
