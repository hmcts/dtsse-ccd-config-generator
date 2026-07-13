# ET CCD JSON-to-Java migration style guide

This guide defines how to migrate the Employment Tribunals (ET) CCD definitions from JSON to the Java CCD config
generator in this repository. It is written for both engineers and automated coding sessions.

The words **must**, **must not**, **should** and **may** are normative. A migration is not complete merely because the
Java compiles or CCD accepts the generated definition: the generated configuration must remain semantically equivalent
to the ET JSON that it replaces.

## Migration contract

1. The existing files under `test-projects/et-ccd-callbacks/ccd-definitions` are the golden definition. Do not edit them
   to make a Java conversion pass.
2. Migrate incrementally. A change must own an explicit, reviewable vertical slice, such as one event and all of its
   fields and permissions for one case type or a deliberately shared pair of case types.
3. Keep unconverted rows supplied by the existing JSON. Generate converted rows into a build directory and combine
   them only for validation or packaging.
4. Prefer the public typed API in `sdk/ccd-config-generator`. If a CCD concept is not a clean fit, leave that part in
   JSON and add a typed, reusable SDK capability before migrating it.
5. Preserve CCD identifiers exactly. Case type IDs, state IDs, event IDs, field IDs, role IDs, fixed-list codes and
   callback paths are external contracts and are case-sensitive.
6. Prefer ordinary domain-oriented Java over a second representation of the spreadsheet. A reader should be able to
   understand an event as an event, not as a list of cells.
7. Semantic parity is the acceptance condition. JSON file boundaries, object property order and row order are not
   semantic; permissions, conditions, callbacks, display metadata and duplicate row counts are.

Do not treat `EtJsonCcdConfig` as the Java form of the definition. `JsonBackedCCDConfig` is a transitional runtime bridge
which reads enough JSON to register legacy callbacks. The golden JSON contains eight case types, while that bridge
currently registers seven:

- `ET_Admin`
- `ET_EnglandWales`
- `ET_EnglandWales_Listings`
- `ET_EnglandWales_Multiple`
- `ET_Scotland`
- `ET_Scotland_Listings`
- `ET_Scotland_Multiple`
- `Pre_Hearing_Deposit`

Always inventory the golden JSON rather than inferring migration coverage from the registered bridge beans.

The existing ET models are the starting point, not proof that the schema is already modelled correctly:

| Case family | Existing root model |
| --- | --- |
| `ET_Admin` | `AdminData` |
| England/Wales and Scotland singles | `CaseData` |
| England/Wales and Scotland listings | `ListingData` |
| England/Wales and Scotland multiples | `MultipleData` |
| `Pre_Hearing_Deposit` | `PreHearingDepositData` |

Check each root and nested type against the golden `CaseField` and `ComplexTypes` rows. Existing callbacks may require a
field to remain a `String` even where CCD gives it a more specific type; that is a reason for explicit CCD metadata, not
for silently changing the runtime payload.

Use the current Java-configured projects as examples of composition and naming:

- [`nfdiv-case-api`](../test-projects/nfdiv-case-api)
- [`sptribs-case-api`](../test-projects/sptribs-case-api)
- [`adoption-cos-api`](../test-projects/adoption-cos-api)

The repository [`README.md`](../README.md) and current SDK source are authoritative for available APIs. Project-local
page builders are conveniences, not SDK features; copy their design only when the same abstraction makes ET clearer.

## Shape of a Java configuration

A case type is assembled from small Spring components implementing `CCDConfig<CaseData, State, UserRole>`. Keep the
case-type identity in one foundational component and put cohesive events, tabs, searches or access configuration in
separate components.

```java
@Component
public class EnglandWalesSingles implements CCDConfig<CaseData, CaseState, UserRole> {

    @Override
    public String groupingKey() {
        return "ET_EnglandWales";
    }

    @Override
    public void configure(ConfigBuilder<CaseData, CaseState, UserRole> builder) {
        builder.caseType("ET_EnglandWales", "Eng/Wales - Singles", "Eng/Wales - Singles");
        builder.jurisdiction("EMPLOYMENT", "Employment", "Employment");
    }
}
```

`groupingKey()` is required when independent case types use the same root data class. ET's England/Wales and Scotland
singles both use `CaseData`; their listing and multiple variants likewise share root model classes. Use the exact case
type ID as the grouping key. Do not create empty subclasses merely to make the generator separate the definitions.

Shared configuration should be expressed as normal Java behaviour. For example, a common event component may accept a
small region or case-type descriptor containing meaningful differences. It must not accept a bag of arbitrary JSON
columns.

## JSON concept to Java mapping

The status column describes the current direction. **Supported** still requires parity tests for ET's exact rows.
**Assess/extend** means that some ET rows use values not represented by the current typed API. **Legacy until typed**
means the JSON must continue to own those rows for now.

| CCD JSON concept | Preferred Java representation | Status and ET notes |
| --- | --- | --- |
| `Jurisdiction` | `builder.jurisdiction(id, name, description)` | Assess/extend. The standard fields are supported, but ET's `Shuttered` column is not emitted; `builder.shutterService(...)` changes case-type permissions and is not a substitute for that column. |
| `CaseType` | One foundational `CCDConfig` calling `builder.caseType(...)` | Assess/extend. ET also uses columns such as `PrintableDocumentsUrl`, `EnableForDeletion` and `RetriesTimeoutURLPrintEvent`, which have no typed API today. |
| `CaseField` | A field on the root domain model, with `@CCD` only for CCD metadata not inferred from the Java type | Supported for standard metadata; assess every ET type and column. |
| `ComplexTypes` | A domain POJO referenced by the root model; `@ComplexType` only where an explicit CCD name or other supported metadata is needed | Supported. Reuse existing ET domain types where their wire shape matches the definition. |
| `FixedLists` | An enum, normally implementing `HasLabel`, with stable JSON codes | Supported. Do not use enum constant names as accidental external IDs. |
| `State` | A state enum with `@CCD(label = ..., hint = ...)` where labels differ from IDs | Supported. ET's mixed-case IDs must remain exact enum values. |
| `CaseRoles` and `AuthorisationCaseType` | A `HasRole` enum whose `getRole()` and `getCaseTypePermissions()` return the exact JSON values | Supported. Include both case roles and user roles relevant to the case type. |
| `RoleToAccessProfiles` | `builder.caseRoleToAccessProfile(role)` with named access profiles, categories and authorisation | Supported. Preserve `idam:` legacy-role handling and the exact comma-separated semantics. |
| `CaseEvent` | `builder.event(id)` followed by a typed state selection/transition, event metadata, callbacks and grants | Assess/extend. Standard transitions and typed callbacks are supported; raw state expressions, arbitrary callback URLs and `SignificantEvent` are not all represented. |
| `CaseEventToFields` | Typed field getters beneath `.fields()`, using page, display-context, condition, label, hint and default-value APIs | Supported for the normal SDK shape. Compare every page and field attribute. |
| `EventToComplexTypes` / `CaseEventToComplexTypes` | `.complex(...)` or `.list(...).complex(...)` nested field configuration | Supported for normal nesting; assess ET rows with uncommon labels, ordering or publish metadata. |
| `AuthorisationCaseEvent` | `.grant(...)`, preferably with named `HasAccessControl` policies for repeated role sets | Supported. The generated rows must match ET semantics, not merely provide broadly similar access. |
| `AuthorisationCaseState` | Inferred from event grants, or explicit `builder.grant(state, permissions, roles)` | Supported. Treat differences from the golden rows as security-relevant. |
| `AuthorisationCaseField` | Inferred from event use plus `@CCD(access = ...)` for explicit field access | Supported. Verify inheritance and read-only search/tab access. |
| `AuthorisationComplexType` | Access annotations/policies and `builder.grantComplexType(...)` | Supported for the typed API; prove exact ET coverage. |
| `CaseTypeTab` | `builder.tab(id, label)` with typed fields, roles, conditions and display context parameters | Supported for standard tabs. Assess ET-specific `Channel` or ordering behaviour before ownership moves. |
| `SearchInputFields`, `SearchResultFields`, `WorkBasketInputFields` and `WorkBasketResultFields` | `searchInputFields()`, `searchResultFields()`, `workBasketInputFields()` and `workBasketResultFields()` | Supported. Prefer typed getters and the SDK convenience fields for case reference, state and dates. |
| `SearchCaseResultFields` / `SearchCasesResultFields` | `searchCasesFields()` | Supported. Preserve nested list-element paths, display parameters and result ordering. |
| `Categories` | `builder.categories(role)` | Supported; preserve category IDs and hierarchy. |
| `SearchCriteria` and `SearchParty` | `builder.searchCriteria()` and `builder.searchParty()` | Supported; keep the CCD path expressions unchanged. |
| `ChallengeQuestion` | `builder.noticeOfChange().challenge(id)` with typed question answers | Supported. Preserve question order, answer paths and case-role formatting. |
| `Banner`, `SearchAlias` and `UserProfile` | No typed generator API | Legacy until typed. Add a focused SDK abstraction if a migrated slice needs to own these rows. |
| Environment-specific JSON | Existing ET environment substitution and include/exclude processing | Legacy until a generated slice can prove equivalent output for non-production and production modes. |

### Fields and CCD types

Every eligible instance field in the root data class is considered part of the generated schema, even without `@CCD`.
Use `@JsonProperty` when the Java property name differs from the established CCD field ID. Use `@JsonIgnore` or
`@CCD(ignore = true)` only when the property genuinely is not part of CCD data.

```java
public class CaseData {

    @JsonProperty("claimant_email_address")
    @CCD(label = "Claimant email address", typeOverride = FieldType.Email)
    private String claimantEmailAddress;
}
```

Do not rename a CCD field merely to make the Java model prettier. A good Java name plus an explicit `@JsonProperty`
preserves both readability and the persisted wire contract.

Prefer a Java type which describes the data. The generator currently infers these common mappings:

| Java shape | Generated CCD shape |
| --- | --- |
| `String` | `Text` |
| `LocalDate` | `Date` |
| `LocalDateTime` | `DateTime` |
| Primitive/wrapper integer and floating-point types | `Number` |
| Enum | `FixedRadioList` |
| `Set<Enum>` | `MultiSelectList` |
| Domain POJO | Complex type named for the Java type, unless overridden |
| Collection | `Collection` with the resolved element type as `FieldTypeParameter` |

Use `@CCD(typeOverride = ..., typeParameterOverride = ...)` where the runtime Java type must remain broader than the
CCD type, for example an existing ET `String` which CCD defines as `Date`, `DynamicList` or `TextArea`. Do not change a
runtime field type during a config migration without checking callback deserialisation, persistence and outbound client
contracts.

Other supported `@CCD` metadata includes label, hint, show condition, regular expression, display order, category,
searchability, minimum/maximum values, access policy and retained-hidden-value behaviour. Copy only meaningful golden
values; do not add annotations just to restate generator defaults.

### Complex types, flattened types and collections

Model a CCD complex field as a meaningful class:

```java
public class CaseData {
    private Claimant claimant;
}

public class Claimant {
    @CCD(label = "First name")
    private String firstName;

    @CCD(label = "Date of birth")
    private LocalDate dateOfBirth;
}
```

Use `@JsonUnwrapped` only when the persisted CCD fields are flat and the Java model deliberately groups them. Preserve
the established prefix and Jackson naming rules. Do not introduce flattening as an incidental part of converting a
definition.

The idiomatic SDK representation of a CCD collection is `List<ListValue<T>>`, where `ListValue` preserves CCD's
`{ "id", "value" }` wire shape:

```java
private List<ListValue<UploadedDocument>> uploadedDocuments;
```

ET already has several custom item wrappers which expose the same wire shape. Do not replace them globally during an
event migration. First check whether the generator resolves the actual value type. In particular, a concrete wrapper
such as `List<DocumentTypeItem>` may currently be inferred as a collection of `DocumentTypeItem`, while
`List<ListValue<DocumentType>>` is unwrapped to `DocumentType`. If the existing wrapper is part of ET's runtime model,
add narrowly typed wrapper support to the generator rather than inventing a duplicate config model.

Within an event page, use `.list(CaseData::getItems)` to enter a `ListValue<T>` collection and `.complex(...)` to enter a
complex object. End the nested builder with `.done()` before returning to its parent.

### Fixed lists, states and roles

A fixed list should be an enum with an explicit wire code and label. Follow the SDK's `HasLabel` and Jackson convention:

```java
public enum HearingType implements HasLabel {
    @JsonProperty("Preliminary")
    PRELIMINARY("Preliminary hearing"),

    @JsonProperty("Final")
    FINAL("Final hearing");

    private final String label;

    HearingType(String label) {
        this.label = label;
    }

    @Override
    public String getLabel() {
        return label;
    }
}
```

Use ET's existing state enums where they contain the exact golden IDs. Do not normalise identifiers such as `Submitted`,
`AWAITING_SUBMISSION_TO_HMCTS` or `Delete` to a single Java naming style: changing them changes the generated state and
transition IDs. Add `@CCD` to supply display labels and title text, not to disguise an ID change.

Roles should use named enum constants but return the exact JSON role from `getRole()`. Keep repeated permission sets in
small, named `HasAccessControl` implementations. Names such as `CaseworkerAccess` communicate intent better than passing
the same long role list to every event.

### Events, pages and callbacks

One cohesive event component is the default unit of migration:

```java
@Component
public class ManageCaseTtl implements CCDConfig<CaseData, CaseState, UserRole> {

    @Override
    public String groupingKey() {
        return "ET_EnglandWales";
    }

    @Override
    public void configure(ConfigBuilder<CaseData, CaseState, UserRole> builder) {
        builder.event("manageCaseTTL")
            .forAllStates()
            .name("Manage Case TTL")
            .grant(Permission.CRU, UserRole.TTL_PROFILE)
            .fields()
                .page("manageCaseTTL")
                .complex(CaseData::getTtl)
                    .readonly(TTL::getSystemTTL)
                    .optional(TTL::getOverrideTTL)
                    .optional(TTL::getSuspended)
                    .done()
                .done();
    }
}
```

This is a shape example, not the ET golden definition for that event. During a real conversion, copy the exact event
name, description, pre/post states, display order, show condition, retry policy, TTL increment, summary/notes flags,
pages, field contexts, defaults, callbacks and grants from the relevant JSON rows.

Use:

- `.initialState(...)` for create events;
- `.forState(...)`, `.forStates(...)` or `.forAllStates()` when the state does not change;
- `.forStateTransition(...)` when it does;
- `.optional(...)`, `.mandatory(...)` and `.readonly(...)` for `CaseEventToFields` display context;
- `.page(...)`, `.pageLabel(...)` and page show conditions for wizard pages;
- method references for about-to-start, mid-event, about-to-submit and submitted callbacks;
- `.grant(...)` or a named access policy for `AuthorisationCaseEvent`.

SDK callbacks are deliberately typed and routed by the runtime. A legacy raw callback URL is not equivalent just
because it points to the same controller today: it may carry environment substitution, retry or local/external routing
semantics. Keep the JSON event until those semantics are represented by a typed SDK API and covered by runtime tests.

If a JSON event uses a state expression which cannot be represented by the state enum APIs, do not place that expression
in an arbitrary string column. Add an explicit event-transition abstraction to the SDK, or leave the event in JSON.

### Permissions, tabs and search

Permissions are behaviour, not formatting. The SDK infers field and state access from events, but the resulting access
must be compared with every golden authorisation row. Never accept a mismatch because the migrated event happens to work
for the role used in a test.

Use explicit `builder.grant(state, permissions, roles)` and `@CCD(access = ...)` only where the intended access is not
correctly inferred. Avoid broad grants added solely to silence a comparison.

Tabs, workbasket fields and search fields should use typed getters where the field belongs to the Java model. String
field IDs are appropriate only for CCD system fields or a public API which explicitly requires them. Preserve role
visibility, conditions, display context parameters and sort order.

## What idiomatic Java means here

Prefer:

- one small class per event or cohesive configuration concern;
- domain constants or enums for stable IDs used in more than one place;
- typed property getters instead of field-name strings;
- named access policies instead of repeated permission matrices;
- small shared functions for genuine England/Wales and Scotland commonality;
- comments explaining an unavoidable CCD constraint, not narrating the builder calls;
- a typed SDK enhancement which is useful outside ET when the existing API is insufficient.

Do not introduce:

- a `Map<String, Object>` or `caseEventColumn(name, value)` escape hatch;
- a record or constructor with a long sequence of nullable strings corresponding to spreadsheet columns;
- `EventSpec`/`FieldSpec` structures which reproduce `CaseEvent` or `CaseEventToFields` rows in Java;
- a giant switch over case type, event and sheet names;
- copied ET JSON under test resources as a second golden source;
- generated Java checked into source control;
- unrelated model clean-up in the same change as a configuration slice.

A useful test is: if the builder DSL and CCD terminology disappeared, would the class still communicate the business
event, its states, its inputs, its callbacks and its access? If not, the representation is too close to the spreadsheet.

## Handling a generator gap

Before extending the SDK, reduce the mismatch to the smallest golden row or group of rows and determine whether it is:

1. a non-semantic default or representation difference;
2. an existing public API being used incorrectly;
3. an ET domain-model shape the resolver cannot currently understand; or
4. a CCD capability not represented by the public API.

For cases 3 and 4, an enhancement must:

- use a typed, domain-named API rather than exposing arbitrary output columns;
- reject invalid combinations at build/configuration time where practical;
- preserve existing generator output unless the change is intentional;
- have focused generator golden tests and API-level tests;
- document how the Java concept maps to CCD semantics;
- be usable by another service with the same CCD need.

Keep the affected ET rows in JSON until the SDK change is merged and the migrated slice passes semantic comparison.

Known areas to assess early include ET's additional `CaseType` columns, raw callback URL forms, `SignificantEvent`, event
state expressions, exact event-to-complex metadata, concrete collection wrappers and tab/channel metadata. This list is
not an exemption for unlisted columns: inventory the entire slice before coding.

## Incremental migration workflow

### 1. Select and inventory a slice

Choose the smallest useful vertical slice. Record the case type(s), event or feature ID, and every affected sheet. Trace
dependencies into fields, complex types, fixed lists, states, roles, authorisation, tabs and search configuration.

Convert both regional variants together only where that makes differences clearer. Shared code must make the differences
explicit; it must not flatten them into loosely typed parameters.

### 2. Confirm the domain model

Find the existing ET root and nested model fields used by the slice. Check their Jackson names and runtime payload shape
against `CaseField` and `ComplexTypes`. Add minimal CCD annotations where enough. If the model cannot express the golden
schema cleanly, stop and classify the gap before changing it.

### 3. Implement with the public SDK

Add small `CCDConfig` components using typed getters, enum states and roles, typed callbacks and named access policies.
Use `groupingKey()` for each shared-model case type. Do not remove or overwrite the legacy rows yet.

### 4. Generate separately

Write Java-generated definitions to a build directory. The eventual parity harness should declare ownership of converted
slices explicitly and overlay only those rows onto the processed legacy definition. Its ownership format is a build-tool
concern; it must not become a Java row-description DSL.

### 5. Compare canonical rows

ET splits logical sheets across many JSON files, while the SDK normally emits one file per sheet and case type. A path-
based directory comparison is therefore insufficient.

The comparison model should be:

```text
sheet name -> multiset of canonical row maps
```

Before comparing:

1. apply the same ET environment substitution and include/exclude rules to the golden input;
2. flatten all files contributing to the same CCD sheet;
3. remove file paths, JSON property ordering and row ordering;
4. canonicalise missing, null, blank and generated default values only where CCD importer behaviour or an existing
   generator test proves them equivalent;
5. retain every meaningful column and retain duplicate row counts.

Report differences using the sheet's identifying columns and the differing values. Do not add a broad ignore list. A new
normalisation rule requires a focused test showing why the two values are semantically equivalent.

### 6. Validate canonical XLSX

After row parity, run ET's existing `ccd-definition-processor` to build the canonical CCD workbook from the legacy and
merged definitions. Compare normalised workbook cells as an end-to-end check. Cover at least:

- `cftlib`, representing the non-production `*-prod.json` exclusion path; and
- `prod`, representing the `*-nonprod.json` exclusion path.

Run another environment when the converted slice contains substitutions which differ from both of those outputs. The
workbook check supplements the row comparison; it does not replace the more precise row-level diagnostics.

### 7. Prove behaviour and transfer ownership

Follow `docs/testing-strategy.md`. Add generator golden tests for SDK changes and cftlib/Spring behaviour tests for the
converted event or feature. Transfer the declared slice from legacy JSON to Java only after row parity, XLSX parity and
behaviour tests pass.

Do not delete or edit the golden JSON during the migration programme. Removing the legacy definition is a separate,
explicit end-state decision after all eight case types have Java ownership and the comparison harness has no legacy rows
left to merge.

## Review checklist

- [ ] The exact case type, feature/event and affected sheets are stated.
- [ ] Existing ET JSON is unchanged.
- [ ] CCD IDs and Jackson wire names are unchanged.
- [ ] Existing domain models are reused or any minimal model change is justified by runtime behaviour.
- [ ] The Java reads as domain/event configuration rather than spreadsheet rows.
- [ ] No arbitrary column API, positional row record or copied golden fixture was introduced.
- [ ] Every unsupported value remains owned by JSON or has a typed SDK enhancement with tests.
- [ ] Generated and golden rows are semantically equal, including permissions and duplicate counts.
- [ ] Normalised XLSX output matches for the required environments.
- [ ] Behaviour tests follow `docs/testing-strategy.md`.
