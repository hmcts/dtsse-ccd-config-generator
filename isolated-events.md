# Isolated DTOs for Decentralised Events

## Context

Decentralised events currently share a single "god" case data class (e.g., `PCSCase` with 700+ fields) even though individual events only use a small subset of those fields. For example, `CreatePossessionClaim` uses 3 fields, `EnforcementOrderEvent` uses only its `enforcementOrder` nested field. This is a legacy of CCD's centralised model where all case data lived in a single JSON blob.

With decentralised data, events are ephemeral payloads exchanged with the frontend - they are NOT stored in CCD's `case_data` table or the service's own database. They should work like conventional request/response payloads.

This plan introduces **isolated DTO classes per event** - plain Java classes with minimal boilerplate. The SDK handles all CCD translation automatically.

## Design Principles

1. **DTOs are flat Java classes** - `@Data`/`@Builder` only. Fields must be primitives, enums, strings, dates, or SDK built-in types (`AddressUK`, `Document`, etc.). Custom nested objects are **not allowed** - the generator errors if it encounters one. `@CCD` is supported as opt-in for labels/show conditions/type overrides. No `@JsonUnwrapped` or `@Access` needed.
2. **Prefixing is fully automatic** - the SDK auto-generates a prefix from the event ID, adds/strips it during serialisation, deserialisation, and CCD definition generation. Developer code never sees prefixed field names and never specifies a prefix.
3. **Event-level access control** - if you can run the event, you get CRU on all DTO fields
4. **Labels auto-generated** from field names, overridable with `@CCD(label=...)` if needed
5. **Field types inferred** from Java types using existing resolver logic

## Before & After Example

### Before (god class pattern)
```java
// 700+ field god class
@Data @Builder
public class PCSCase {
    @CCD(label = "Property address", access = {CitizenAccess.class})
    @External
    private AddressUK propertyAddress;

    @CCD(searchable = false)
    private YesOrNo showCrossBorderPage;

    @JsonUnwrapped
    private EnforcementOrder enforcementOrder;
    // ... 700 more fields
}

// Event receives entire god class
public class CreatePossessionClaim implements CCDConfig<PCSCase, State, UserRole> {
    public void configureDecentralised(DecentralisedConfigBuilder<PCSCase, State, UserRole> b) {
        b.decentralisedEvent("createPossessionClaim", this::submit, this::start)
            .initialState(State.AWAITING_FURTHER_CLAIM_DETAILS)
            .grant(Permission.CRUD, UserRole.PCS_SOLICITOR);
    }
    // Handler gets PCSCase with 700 fields, uses 3
    private SubmitResponse<State> submit(EventPayload<PCSCase, State> p) {
        pcsCaseService.createCase(p.caseReference(), p.caseData().getPropertyAddress(), ...);
    }
}
```

### After (isolated DTO pattern)
```java
// Small, focused DTO - plain Java, no CCD annotations
@Data @Builder
public class CreateClaimData {
    private AddressUK propertyAddress;
    private LegislativeCountry legislativeCountry;
    private String feeAmount;
    private YesOrNo showCrossBorderPage;
}

// Event declares its own DTO class - no prefix to specify
public class CreatePossessionClaim implements CCDConfig<PCSCase, State, UserRole> {
    public void configureDecentralised(DecentralisedConfigBuilder<PCSCase, State, UserRole> b) {
        b.decentralisedEvent("createPossessionClaim", CreateClaimData.class, this::submit, this::start)
            .initialState(State.AWAITING_FURTHER_CLAIM_DETAILS)
            .grant(Permission.CRUD, UserRole.PCS_SOLICITOR);
    }
    // Handler gets only the DTO it needs - plain Java, no prefixes
    private SubmitResponse<State> submit(EventPayload<CreateClaimData, State> p) {
        pcsCaseService.createCase(p.caseReference(), p.caseData().getPropertyAddress(), ...);
    }
}
```

SDK auto-generates prefix from event ID (`createPossessionClaim`). Generated CCD fields (developer never sees these): `createPossessionClaimPropertyAddress` (AddressUK), `createPossessionClaimLegislativeCountry` (FixedRadioList), etc. Access: auto CRU for PCS_SOLICITOR on all fields.

## Implementation Plan

### Step 1: SDK API Changes

**Files to modify:**

- `sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/api/DecentralisedConfigBuilder.java`
  - Deprecate the existing overloads that use the god case data class:
  ```java
  @Deprecated
  EventTypeBuilder<T, R, S> decentralisedEvent(String id, Submit<T, S> submitHandler);
  @Deprecated
  EventTypeBuilder<T, R, S> decentralisedEvent(String id, Submit<T, S> submitHandler, Start<T, S> startHandler);
  ```
  - Add new overloads that accept a DTO class (prefix auto-derived from event ID):
  ```java
  <D> EventTypeBuilder<D, R, S> decentralisedEvent(
      String id, Class<D> dtoClass,
      Submit<D, S> submitHandler);
  <D> EventTypeBuilder<D, R, S> decentralisedEvent(
      String id, Class<D> dtoClass,
      Submit<D, S> submitHandler, Start<D, S> startHandler);
  ```
  - The event ID is used as the prefix automatically. Since CCD field names are internal and the developer never sees them, length doesn't matter.

- `sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/api/Event.java`
  - Add DTO metadata fields to `Event`:
  ```java
  private Class<?> dtoClass;   // null for non-DTO events
  private String dtoPrefix;    // null for non-DTO events
  ```
  - Add `isDtoEvent()` convenience method
  - Add `dtoClass` and `dtoPrefix` to `EventBuilder` (private setters, set by builder factory)
  - In `EventBuilder.builder()`: add overload that accepts `dtoPrefix` and sets `unwrappedParentPrefix` on the FieldCollectionBuilder

- `sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/api/EventPayload.java`
  - No change needed - `EventPayload<D, S>` already works with any type parameter D. The `caseData` field will hold the DTO instance.

### Step 2: Config Builder Implementation

**Files to modify:**

- `sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/ConfigBuilderImpl.java`
  - Implement the new `decentralisedEvent` overloads
  - Auto-derive prefix from event ID
  ```java
  @Override
  public <D> EventTypeBuilder<D, R, S> decentralisedEvent(
      String id, Class<D> dtoClass,
      Submit<D, S> submitHandler, Start<D, S> startHandler) {
    return new DtoEventTypeBuilderImpl<>(config, events, id, dtoClass, id, submitHandler, startHandler);
    //                                                               ^^^ event ID used as prefix
  }
  ```

- `sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/DtoEventTypeBuilderImpl.java` (NEW)
  - Extends/mirrors `EventTypeBuilderImpl` but:
    - Uses `dtoClass` instead of `config.caseClass` as the `dataClass` for `Event.EventBuilder.builder()`
    - Sets `dtoPrefix` on the event
    - Sets `unwrappedParentPrefix` on the FieldCollectionBuilder to the prefix value
  - This means all field IDs generated via PageBuilder will automatically be prefixed (reuses existing `FieldCollectionBuilder.createField()` logic: `prefix + capitalize(fieldName)`)

### Step 3: Generator Changes

**Files to modify:**

- `sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/generator/CaseFieldGenerator.java`
  - In `write()`: after generating fields from `config.getCaseClass()`, iterate over events with `dtoClass != null`
  - For each DTO event, use `appendFields(fields, dtoClass, caseTypeId, eventPrefix)` to generate flat CCD fields with the event prefix
  - **DTO validation**: before generating fields, validate the DTO class is flat:
    - Allowed field types: primitives, boxed primitives, `String`, `LocalDate`, `LocalDateTime`, enums, SDK built-in types (`AddressUK`, `Document`, `DynamicList`, `DynamicStringList`, etc.), `YesOrNo`
    - **Error at generation time** if any field is a custom user class (not an SDK type or enum)
    - This prevents accidental use of nested objects that the runtime wouldn't know how to handle
  - Reuse existing `resolveFieldType()` and `populateFieldMetadata()` for each field

- `sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/generator/AuthorisationCaseFieldGenerator.java`
  - For DTO events: auto-generate CRU (Create, Read, Update) entries for ALL DTO fields for ALL roles that have event-level grants
  - E.g., if event grants CRUD to PCS_SOLICITOR, generate AuthorisationCaseField entries giving CRU on every `createPossessionClaim*` field to PCS_SOLICITOR
  - No need for `@Access` annotations on DTO fields

- `sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/generator/CaseEventToFieldsGenerator.java`
  - No changes needed - it already reads from `event.getFields()` (the FieldCollection), which will have prefixed field IDs from the FieldCollectionBuilder

- `sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/generator/CaseEventGenerator.java`
  - No changes needed - event metadata (callbacks, states, grants) works the same

### Step 4: Runtime Changes - Transparent Prefix Handling

The prefix is entirely invisible to the developer. The SDK handles it at two boundaries:
- **Inbound (CCD → handler)**: CCD sends data with prefixed keys (`createPossessionClaimPropertyAddress`). The SDK strips the prefix and deserialises into a plain DTO (`propertyAddress`). The handler receives a normal Java object.
- **Outbound (handler → CCD)**: The handler returns a plain DTO. The SDK serialises it and adds the prefix to each key before sending to CCD.

**New utility class** - `sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/runtime/DtoMapper.java` (NEW)

  Handles prefix stripping/adding at the CCD ↔ handler boundary. Since DTOs are flat, this is straightforward key renaming.

  - `<D> D fromCcdData(Map<String, Object> data, String prefix, Class<D> dtoClass, ObjectMapper mapper)`:
    1. Filter keys that start with the event prefix
    2. Strip the prefix and uncapitalise the first char to get DTO field names
    3. Build a simple flat map of `{fieldName → value}`
    4. Use Jackson `mapper.convertValue(flatMap, dtoClass)` for conversion

  - `Map<String, Object> toCcdData(Object dto, String prefix, ObjectMapper mapper)`:
    1. Convert DTO to a flat map via `mapper.convertValue(dto, Map.class)`
    2. Add event prefix + capitalise to each key
    3. Return the prefixed flat map for CCD

**Files to modify:**

- `sdk/decentralised-runtime/src/main/java/uk/gov/hmcts/ccd/sdk/impl/DecentralisedSubmissionHandler.java`
  - In `prepareSubmitHandler()`: check if event has a `dtoClass`
  - If DTO event: use `DtoMapper.fromCcdData()` to strip prefix and deserialise into the flat DTO
  ```java
  if (eventConfig.getDtoClass() != null) {
      Object dtoData = DtoMapper.fromCcdData(
          event.getCaseDetails().getData(), eventConfig.getDtoPrefix(),
          eventConfig.getDtoClass(), mapper);
      return eventConfig.getSubmitHandler()
          .submit(new EventPayload(caseRef, dtoData, urlParams));
  }
  ```

- `sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/runtime/CcdCallbackExecutor.java`
  - `aboutToStart()`: for DTO events, `DtoMapper.fromCcdData()` → call start handler → `DtoMapper.toCcdData()` back
  - `midEvent()`: same pattern - reconstruct DTO inbound, flatten outbound
  - The developer's handler code only ever sees plain DTO objects - no prefixes

- `sdk/decentralised-runtime/src/main/java/uk/gov/hmcts/ccd/sdk/impl/CaseSubmissionService.java`
  - No changes needed - persisted case data comes from `CaseView.getCase()` which only returns persisted data (DTO fields are ephemeral, not in the service DB, so naturally excluded)

### Step 5: Resolve Event Storage Type Parameter Issue

The `ResolvedCCDConfig` has `ImmutableMap<String, Event<T, R, S>> events` where T is the CaseData class. DTO events would have their callbacks typed on D (the DTO class), not T.

Since Java generics are erased at runtime:
- The `Event.dataClass` field is already raw (`Class` not `Class<T>`)
- Callbacks stored as `Submit<T, S>` at compile time actually hold `Submit<D, S>` references at runtime (safe due to erasure)
- Generators already access events with raw types in places (e.g., `CaseFieldGenerator` line 62: `for (Event event : ...)`)
- The `events` map type in `ResolvedCCDConfig` should be widened to `ImmutableMap<String, Event<?, R, S>>` to cleanly accommodate mixed types

**File to modify:**
- `sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/ResolvedCCDConfig.java`
  - Change events type to `ImmutableMap<String, Event<?, R, S>>`
  - Update `ConfigBuilderImpl.getEvents()` accordingly

### Step 6: Update PCS Test Project

**Page building is unchanged in the SDK.** The existing `FieldCollectionBuilder` is already generic — it works with any class, not just the god case class. Method references in page config simply point at DTO fields instead of `PCSCase` fields. The `unwrappedParentPrefix` mechanism already handles auto-prefixing field IDs in page config.

PCS has a thin `PageBuilder` wrapper hardcoded to `PCSCase`. For DTO events, either make it generic (`PageBuilder<T>`) or use `eventBuilder.fields().page(id)` directly. This is a PCS consumer change, not an SDK change.

**Migration of CreatePossessionClaim** (first migration target — 5 pages, ~10 DTO fields, start + submit handlers):

- Create `src/main/java/uk/gov/hmcts/reform/pcs/ccd/dto/CreateClaimData.java`:
  ```java
  @Data @Builder
  public class CreateClaimData {
      private AddressUK propertyAddress;
      private LegislativeCountry legislativeCountry;
      private String feeAmount;
      private YesOrNo showCrossBorderPage;
      private YesOrNo showPropertyNotEligiblePage;
      private YesOrNo showPostcodeNotAssignedToCourt;
      private DynamicStringList crossBorderCountriesList;
      private LegislativeCountry crossBorderCountry1;
      private LegislativeCountry crossBorderCountry2;
      private String formattedPropertyAddress;
  }
  ```
- Update `CreatePossessionClaim.java` to use new `decentralisedEvent` overload with DTO class:
  ```java
  b.decentralisedEvent("createPossessionClaim", CreateClaimData.class, this::submit, this::start)
  ```
- Update page configurations (StartTheService, EnterPropertyAddress, etc.) to reference `CreateClaimData` fields instead of `PCSCase` fields
- Make PCS `PageBuilder` generic or use `eventBuilder.fields().page(id)` directly

**Subsequent migrations** (after CreatePossessionClaim is proven):
- CitizenSubmitApplication, CitizenCreateApplication, CitizenUpdateApplication — simple events with no pages, good for validating the no-page DTO path
- EnforcementOrderEvent — 16 pages, ~20 flat DTO fields, validates the pattern at larger scale
- ResumePossessionClaim — largest event (58 pages, ~150+ fields), migrate last

## How Prefixing Works (Transparent to Developer)

The prefix is an internal CCD namespace mechanism. The developer writes plain Java code - the prefix never appears in their DTO classes, handlers, or page configurations.

**Where the prefix IS visible** (CCD internals only):
- `CaseField.json`: `{"ID": "createPossessionClaimPropertyAddress", "FieldType": "AddressUK"}`
- `CaseEventToFields/createPossessionClaim.json`: `{"CaseFieldID": "createPossessionClaimPropertyAddress"}`
- `AuthorisationCaseField/`: entries for `createPossessionClaimPropertyAddress`

**Where the prefix is NOT visible** (developer code):
- DTO class: `private AddressUK propertyAddress;`
- Handler: `payload.caseData().getPropertyAddress()`
- Page config: `.mandatory(CreateClaimData::getPropertyAddress)`

**Implementation leverages existing mechanisms:**
- `FieldCollectionBuilder.unwrappedParentPrefix` auto-prefixes field IDs in page config (already built)
- `CaseFieldGenerator.appendFields(fields, dataClass, caseTypeId, idPrefix)` generates prefixed CaseField entries (already built)
- NEW: `DtoMapper` strips/adds prefix at runtime boundaries (CCD ↔ handler)

Prefix convention: camelCase concatenation (event ID + capitalised field name, e.g. `createPossessionClaim` + `PropertyAddress` = `createPossessionClaimPropertyAddress`)

## Flat DTOs Only (No Custom Nested Objects)

DTOs must be flat. All fields must be one of:
- Primitives and boxed primitives (`int`, `Integer`, `long`, `Long`, `float`, `double`, etc.)
- `String`, `LocalDate`, `LocalDateTime`
- Enums (generated as `FixedRadioList`)
- SDK built-in types (`AddressUK`, `Document`, `DynamicList`, `DynamicStringList`, `YesOrNo`, etc.) - preserved as CCD ComplexType references since CCD has native renderers for them

**Custom user classes are not allowed.** The generator will **error at config generation time** if a DTO field is a type it doesn't recognise. This keeps the implementation simple and avoids the complexity of auto-flattening nested objects or generating ComplexTypes from DTO internals.

For a DTO like:
```java
@Data @Builder
class EnforcementOrderData {
    private SelectEnforcementType selectEnforcementType;  // Enum → FixedRadioList
    private AddressUK evictionAddress;                    // SDK type → ComplexType
    private YesNoNotSure anyRiskToBailiff;                // Enum → FixedRadioList
    private String additionalInfo;                        // String → Text
    private String moneyOwed;                             // String → Text
}
```

Generated CCD output (all top-level, event prefix applied automatically):
- `enforceTheOrderSelectEnforcementType` → FixedRadioList
- `enforceTheOrderEvictionAddress` → AddressUK (ComplexType)
- `enforceTheOrderAnyRiskToBailiff` → FixedRadioList
- `enforceTheOrderAdditionalInfo` → Text
- `enforceTheOrderMoneyOwed` → Text

Pages reference DTO fields directly - no `.complex()` needed for grouping:
```java
pageBuilder.page("additionalInfo")
    .mandatory(EnforcementOrderData::getAdditionalInfo)
    .optional(EnforcementOrderData::getMoneyOwed);
```

**Runtime deserialization** is simple: the SDK strips the event prefix from CCD keys, uncapitalises, and uses Jackson `convertValue()` to deserialise the flat map into the DTO class. No nested object reconstruction needed.

## First Goal: Port CreatePossessionClaim End-to-End

The first milestone is migrating the `CreatePossessionClaim` event to use an isolated DTO and verifying it works end-to-end in a running system. This proves the entire pipeline: API → builder → generators → runtime prefix handling → CCD UI → handlers.

### Verification Steps

1. **Generate config**: Run `./gradlew :test-projects:pcs-api:generateCCDConfig` and verify:
   - `CaseField.json` contains prefixed DTO fields (`createPossessionClaimPropertyAddress`, etc.)
   - `CaseEventToFields/createPossessionClaim.json` references the prefixed fields
   - `AuthorisationCaseField/` has auto-generated CRU entries for DTO fields

2. **Run existing tests**: `./gradlew check` — all golden file tests, integration tests, and test project builds must stay green. No regressions in existing events.

3. **Boot PCS with CCD**: `./gradlew :test-projects:pcs-api:bootWithCCD`

4. **Manual E2E verification in the browser** using Chrome DevTools MCP:
   - Open the CCD UI and start the CreatePossessionClaim event
   - Verify the start handler fires (fee amount field populated)
   - Walk through all 5 pages — fields render correctly, show conditions work, mid-event callbacks fire
   - Submit the event
   - Verify the submit handler receives the DTO with correct data (check service logs)
   - Verify DTO fields are NOT in persisted case data (inspect the case view — DTO fields should not appear)
   - Verify other existing events still work (run a different event to confirm no regressions)
