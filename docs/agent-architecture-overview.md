# Architecture Overview

## Project Structure

This is a composite Gradle build. The root `settings.gradle` includes:

- `sdk/` — included as a composite build (`includeBuild 'sdk'`). Contains 5 subprojects:
  - `ccd-config-generator` — core library. Generates CCD JSON definitions from annotated Java classes. Contains the API (`sdk/api/`), config builders, generators, and runtime callback support. No Spring Boot dependency.
  - `decentralised-runtime` — Spring Boot runtime for decentralised CCD events. Depends on ccd-config-generator. Handles event submission via `DecentralisedSubmissionHandler`, case persistence via `CaseSubmissionService`.
  - `ccd-gradle-plugin` — Gradle plugin providing `generateCCDConfig` task.
  - `ccd-servicebus-support` — Azure Service Bus integration for async event publishing.
  - `cftlib-dev-only` — Dev utilities for the CCD test library (embedded CCD stack).
- `test-projects/e2e` (gradle name: `e2e`) — end-to-end tests with embedded CCD stack. Has `SimpleCaseConfiguration` and `DivorceConfiguration`. Run with `e2e:cftlibTest`.
- `test-projects/pcs-api` (gradle name: `pcs`) — PCS service. Uses decentralised events. Primary test bed for new SDK features.
- `test-projects/nfdiv-case-api`, `sptribs-case-api`, `adoption-cos-api` — other service test projects.
- `ccd-data-store-api/` — optionally included if directory exists (for local CCD development).

## CCD & Architecture

Services & CCD are cyclically dependent:
* ccd-data-store-api makes callbacks to services based on their CCD definitions
* services may initiate CCD events by calling ccd-data-store-api

CCD has a **flat field model** — all case fields are top-level key-value pairs in a single JSON blob (`case_data` column). There are no nested structures except for CCD's built-in ComplexTypes (`AddressUK`, `Document`, etc.) which CCD has native renderers for.

## Config Generation Pipeline

Services define events by implementing `CCDConfig<CaseData, State, UserRole>`. The SDK discovers these via Spring, builds an in-memory model, then generates ~20 JSON definition files.

**Flow:** `CCDConfig.configure(builder)` → `ConfigBuilderImpl` (mutable) → `ResolvedCCDConfig` (immutable) → `JSONConfigGenerator` → individual `ConfigGenerator` implementations → JSON files.

**Key generated files:**
- `CaseField.json` — all case fields with types and labels
- `CaseEvent.json` — events with state transitions, callbacks, webhooks
- `CaseEventToFields/` — per-event field mappings (display context, page layout, ordering)
- `AuthorisationCaseField.json` — role→field permissions (CRUD)
- `AuthorisationCaseEvent.json` — role→event permissions
- `AuthorisationCaseState.json` — role→state permissions
- `ComplexTypes/` — nested object schemas (depth-ordered)
- `FixedList/` — enum→fixed list mappings
- `CaseTypeTab.json`, `WorkBasket.json`, `SearchInput.json`, `SearchResult.json` — UI config

All generators implement `ConfigGenerator<T, S, R>` and are Spring components auto-collected by `JSONConfigGenerator`.

## Event Model

Events are configured via `ConfigBuilder.event("id")` (legacy) or `DecentralisedConfigBuilder.decentralisedEvent("id", submitHandler)` (decentralised).

**Legacy events** use CCD callbacks: `aboutToStartCallback`, `aboutToSubmitCallback`, `submittedCallback`. CCD calls the service at these URLs.

**Decentralised events** use `Submit<T,S>` and optional `Start<T,S>` handlers. The service's `DecentralisedSubmissionHandler` calls handlers directly instead of going through CCD callback URLs.

**Key classes:**
- `Event<T, R, S>` — immutable event model. Has callbacks, grants, FieldCollection.
- `Event.EventBuilder` — builder with nested `FieldCollectionBuilder` for page/field config.
- `EventTypeBuilderImpl` — creates EventBuilders for each state transition pattern.
- `FieldCollection` / `FieldCollectionBuilder` — tracks fields, pages, show conditions, mid-event callbacks for an event.
- `ResolvedCCDConfig` — immutable holder of all resolved config for a case type.
- `ResolvedConfigRegistry` — Spring singleton indexing configs by case type. Used at runtime to look up events and their handlers.
- `ConfigBuilderImpl` — implements `DecentralisedConfigBuilder`. Mutable collector during config phase.

## Type Resolution (Java → CCD)

`CaseFieldGenerator` resolves Java field types to CCD field types:
- `String` → `Text` (or `FixedList` if `@CCD(typeParameterOverride=...)` set)
- `LocalDate` → `Date`, `LocalDateTime` → `DateTime`
- `int/Integer/long/Long/float/double/...` → `Number`
- Enums → `FixedRadioList` with enum class name as parameter
- `Set<SomeEnum>` → `MultiSelectList`
- `List<ListValue<T>>` / `Collection<T>` → `Collection` with element type as parameter
- Classes with `@ComplexType(name="X")` → use that name
- `@CCD(typeOverride=...)` always takes precedence

Resolution code: `CaseFieldGenerator.resolveFieldType()` → `resolveSimpleType()` / `resolveCollectionType()`. Uses Spring `ResolvableType` to resolve generic collection element types.

## @JsonUnwrapped and Field Prefixing

CCD's flat model requires flattening nested Java objects. `@JsonUnwrapped(prefix="applicant1")` on a field causes:
- `CaseFieldGenerator.appendUnwrapped()` recursively processes the nested class's fields with the prefix prepended to each field ID
- The parent field is NOT registered as a CaseField or ComplexType
- Child fields appear at top level: `applicant1FirstName`, `applicant1LastName`

At the page-building level, `FieldCollectionBuilder.unwrappedParentPrefix` auto-prefixes field IDs:
```java
// In FieldCollectionBuilder.createField():
String fieldId = (unwrappedParentPrefix != null && !unwrappedParentPrefix.isEmpty())
    ? unwrappedParentPrefix.concat(capitalize(id))
    : id;
```
When `.complex(CaseData::getApplicant1)` is called on an `@JsonUnwrapped` field, a child FieldCollectionBuilder is created sharing the same field lists but with the prefix set. All fields added through it get prefixed automatically.
