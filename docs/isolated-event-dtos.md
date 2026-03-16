# Proposal: Isolated Event DTOs

Decentralised events can define their own **isolated DTO class** instead of sharing a case-wide data class. Each event defines a specific, focused Java class containing only the fields it needs:

```java
@Data
@Builder
public class CreateClaimData {
    private AddressUK propertyAddress;
    private LegislativeCountry legislativeCountry;
    private String feeAmount;
    private YesOrNo showCrossBorderPage;
}
```

The DTO class is registered when you declare the event:

```java
builder.decentralisedEvent("createPossessionClaim", CreateClaimData.class, this::submit)
    .initialState(State.AWAITING_FURTHER_CLAIM_DETAILS)
    .name("Make a claim")
    .grant(Permission.CRUD, UserRole.PCS_SOLICITOR);
```

Your handlers receive the DTO directly:

```java
private SubmitResponse<State> submit(EventPayload<CreateClaimData, State> payload) {
    CreateClaimData data = payload.caseData();
    caseService.createCase(payload.caseReference(), data.getPropertyAddress(), ...);
    return SubmitResponse.of(State.AWAITING_HEARING);
}
```

Different events are completely isolated from each other — they cannot see or interfere with each other's fields nor the main case data view class.

## Key concepts

### Encapsulation

Rather than all events sharing a single case-wide data class, each event encapsulates its own data in a focused DTO. Events cannot see or modify each other's fields, and there is no shared mutable state between them.

This also makes the separation between reading and writing explicit. Your `CaseView` composes a read-only view of the case (rendered by XUI or CUI as tabs, labels, summaries etc.), while each event defines its own independent input schema as a DTO class. The two never overlap.

### DTOs are ephemeral

They are not automatically persisted anywhere. They exist only for the lifetime of a CCD event: rendered to the user as a form, submitted back, and then gone.

### You hydrate them

If your event form needs pre-populated data (e.g. a fee amount, a default address), provide a start handler that builds and returns the DTO. The SDK passes it to CCD for rendering.

DTOs will always start empty by default.

### You persist what you need

Your submit handler receives the completed DTO. It is your responsibility to extract the data you need and write it to your own database.

### You own concurrency control

There is no automatic optimistic locking on DTO based events. If your event writes to shared state or has other concurrency requirements, you are responsible for implementing appropriate concurrency controls (row-level locking, optimistic versioning, etc.) in your own persistence layer.

## A simpler model

When CCD manages your data persistence, it needs to understand the structure of your data — hence `ComplexType`, `@JsonUnwrapped`, nested objects, and collection wrappers. Your data model has to be expressed in CCD's type system so CCD can store and retrieve it correctly.

With decentralised persistence CCD doesn't store your data. Events become conventional request/response payloads: a set of input fields, submitted to your handler, which persists them however your application requires.

This removes the need for several CCD-specific modelling concepts:

- **No custom complex types.** You don't need to define nested structures for CCD to store on your behalf. CCD's built-in complex types (`AddressUK`, `Document`, etc.) are still available where you need CCD to render a specific UI component (e.g. a postcode lookup for addresses). But you don't need to create your own — if your event collects an address and a name, those are just fields on a flat DTO. How you store them is your business.
- **No `@JsonUnwrapped`.** That exists for packing nested objects into CCD's flat field model. With flat DTOs there's nothing to unwrap.
- **No `@Access` annotations on fields.** When all events share the same data class, different roles may need different access to the same fields depending on context. With isolated DTOs each event has its own fields, so field-level access distinctions are unnecessary (see [Access control](#access-control) below).
- **`@CCD` behaves as before.** Use it for label overrides or type overrides if the defaults don't suit you. If omitted, labels are auto-generated from the field name and types are inferred from the Java type.

### Allowed field types

DTO fields must be one of:

- Primitives and boxed types (`int`, `Integer`, `Long`, `Double`, etc.)
- `String`, `LocalDate`, `LocalDateTime`
- Enums
- SDK built-in types (`AddressUK`, `Document`, `DynamicList`, `YesOrNo`, etc.)
- Collections of the above

The config generator will error at build time if it encounters a field type it cannot map to a CCD input.

## Access control

When all events share a single data class, access control must be configured per-field — a field like `propertyAddress` might need different permissions depending on which event is using it and which role is running it. This requires `@Access` annotations, explicit `AuthorisationCaseField` entries, and careful coordination across events.

With isolated DTOs, each event owns its fields exclusively. Access control reduces to a single question: **can this role run this event?**

```java
.grant(Permission.CRUD, UserRole.PCS_SOLICITOR)
```

If a role is granted permission on the event, it automatically gets full CRUD access to all of that event's fields. No per-field annotations needed. Your authorisation logic lives in one place — the event grant — rather than being spread across field annotations and generated config.

For finer-grained control (e.g. conditionally hiding a field based on the current user), implement that in your start handler or mid-event callback using application code.

## Field references and prefixing

CCD field names are still derived from Java field names, but isolated DTO events add an automatic event-specific prefix so
multiple events can reuse the same DTO property names without colliding (see
[Field isolation via prefixing](#field-isolation-via-prefixing)).

For page field bindings, you continue to use Java method references:

```java
.mandatory(CreateClaimData::getPropertyAddress)
.optional(CreateClaimData::getFeeAmount)
```

For show conditions and interpolated labels, you still write strings, but you write them using the DTO field names and
the generator applies the DTO prefix when it writes the CCD JSON:

```java
.showCondition("showCrossBorderPage=\"Yes\"")
.label("info", "The claim fee is ${feeAmount}.")
```

### Page fields

Page field bindings already use method references:

```java
.mandatory(CreateClaimData::getPropertyAddress)
.optional(CreateClaimData::getFeeAmount)
```

### Labels

Interpolated labels use CCD `${...}` expressions with the DTO field names:

```java
.label("info", "The claim fee is ${feeAmount}.")
```

Static labels remain plain strings:

```java
.label("info", "Enter the property address below.")
```

## Example

### 1. Define a DTO

A plain Java class with the fields your event uses:

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateClaimData {
    private AddressUK propertyAddress;
    private LegislativeCountry legislativeCountry;
    private String feeAmount;
    private YesOrNo showCrossBorderPage;
}
```

### 2. Register the event with the DTO class

Pass the DTO class to `decentralisedEvent`:

```java
public void configureDecentralised(DecentralisedConfigBuilder<PCSCase, State, UserRole> builder) {
    EventBuilder<CreateClaimData, UserRole, State> event = builder
        .decentralisedEvent("createPossessionClaim", CreateClaimData.class, this::submit, this::start)
        .initialState(State.AWAITING_FURTHER_CLAIM_DETAILS)
        .name("Make a claim")
        .grant(Permission.CRUD, UserRole.PCS_SOLICITOR);

    new StartTheService().addTo(event);
    new EnterPropertyAddress().addTo(event);
}
```

### 3. Write handlers

The **start handler** hydrates the DTO before CCD renders the form. Return any fields you want pre-populated:

```java
private CreateClaimData start(EventPayload<CreateClaimData, State> payload) {
    BigDecimal fee = feeService.getFeeAmount(FeeTypes.GENERAL_APPLICATION);
    return CreateClaimData.builder()
        .feeAmount(fee.toString())
        .build();
}
```

The **submit handler** receives the completed DTO. Extract what you need and persist it:

```java
private SubmitResponse<State> submit(EventPayload<CreateClaimData, State> payload) {
    CreateClaimData data = payload.caseData();
    caseService.createCase(payload.caseReference(), data.getPropertyAddress(), ...);
    return SubmitResponse.of(State.AWAITING_HEARING);
}
```

### 4. Configure pages

Page classes reference DTO fields directly:

```java
public class EnterPropertyAddress {
    public void addTo(EventBuilder<CreateClaimData, UserRole, State> eventBuilder) {
        eventBuilder.fields()
            .page("enterPropertyAddress")
            .mandatory(CreateClaimData::getPropertyAddress);
    }
}
```

Labels and show conditions still use DTO field names rather than the generated prefixed CCD field names:

```java
.label("info", "The claim fee is ${feeAmount}.")
.showCondition("showCrossBorderPage=\"Yes\"")
```

## Migrating from the shared data class

1. Identify which fields an event actually uses
2. Create a DTO class with those fields
3. Switch from `decentralisedEvent(id, submit, start)` to `decentralisedEvent(id, DtoClass.class, submit, start)`
4. Update handler signatures from `EventPayload<PCSCase, State>` to `EventPayload<DtoClass, State>`
5. Update page classes to reference DTO getters, and update any string show conditions or label interpolations to use the DTO field names
6. Remove orphaned fields from the shared class that are no longer used by any event or view

## Implementation details

### Field isolation via prefixing

CCD field names are limited to 70 characters and share a flat namespace across the entire case type. The SDK keeps each event's fields isolated by automatically prefixing DTO field names with a compact prefix derived from the event ID.

The prefix is the **camelCase initials** of the event ID — the first letter of each word:

| Event ID | Prefix |
|---|---|
| `createPossessionClaim` | `cpc` |
| `resumePossessionClaim` | `rpc` |
| `enforceTheOrder` | `eto` |
| `citizenCreateApplication` | `cca` |
| `citizenSubmitApplication` | `csa` |
| `citizenUpdateApplication` | `cua` |

This keeps prefixes short (typically 3-4 chars) while remaining deterministic and readable. Since event IDs are unique within a case type, collisions are unlikely — but the config generator checks for them at build time and errors if two DTO events produce the same prefix.

For the `CreateClaimData` example with event `createPossessionClaim` (prefix `cpc`), the generated `CaseField.json` contains:

```json
[
  { "ID": "cpcPropertyAddress",    "FieldType": "AddressUK" },
  { "ID": "cpcLegislativeCountry", "FieldType": "FixedRadioList" },
  { "ID": "cpcFeeAmount",          "FieldType": "Text" },
  { "ID": "cpcShowCrossBorderPage", "FieldType": "YesOrNo" }
]
```

A second event `enforceTheOrder` (prefix `eto`) with its own DTO would produce fields like `etoEnforcementType`, `etoRiskToBailiff`, etc. The prefixes guarantee that events cannot collide, even if their DTOs use the same field names.

Developer code does not need to use these prefixed names directly. Page field bindings use method references and the
generator rewrites DTO field-name references in supported string expressions when it writes CCD config:

| Developer writes | SDK generates |
|---|---|
| `"Fee: ${feeAmount}."` | `Fee: ${cpcFeeAmount}.` |
| `"showCrossBorderPage=\"Yes\""` | `cpcShowCrossBorderPage="Yes"` |
| `.mandatory(Dto::getPropertyAddress)` | `CaseFieldID: cpcPropertyAddress` |

### Event-to-fields mapping

`CaseEventToFields/createPossessionClaim.json` maps the prefixed fields to the event's pages:

```json
[
  { "CaseFieldID": "cpcMainContent",     "PageID": "startTheService",      "DisplayContext": "READONLY" },
  { "CaseFieldID": "cpcFeeAmount",       "PageID": "startTheService",      "DisplayContext": "READONLY" },
  { "CaseFieldID": "cpcPropertyAddress", "PageID": "enterPropertyAddress", "DisplayContext": "MANDATORY" }
]
```

### Authorisation

`AuthorisationCaseField` entries are auto-generated, giving CRUD to every role granted on the event:

```json
[
  { "CaseFieldID": "cpcPropertyAddress",    "UserRole": "caseworker-pcs-solicitor", "CRUD": "CRUD" },
  { "CaseFieldID": "cpcLegislativeCountry", "UserRole": "caseworker-pcs-solicitor", "CRUD": "CRUD" },
  { "CaseFieldID": "cpcFeeAmount",          "UserRole": "caseworker-pcs-solicitor", "CRUD": "CRUD" },
  { "CaseFieldID": "cpcShowCrossBorderPage", "UserRole": "caseworker-pcs-solicitor", "CRUD": "CRUD" }
]
```

### Runtime prefix handling

At runtime, the SDK transparently translates between CCD's prefixed field names and the plain DTO field names. Developer handlers never see prefixed keys.
Frontend and test consumers get the same behavior via generated TypeScript client bindings; see
[ts-bindings.md](./ts-bindings.md).

**Start handler (outbound):** The start handler returns a plain DTO. The SDK adds the prefix before sending to CCD:

```
Handler returns:              { "feeAmount": "£404" }
SDK sends to CCD:             { "cpcFeeAmount": "£404" }
```

**Submit handler (inbound):** CCD sends prefixed data. The SDK strips the prefix and deserialises into the DTO:

```
CCD sends:                    { "cpcPropertyAddress": {...}, "cpcFeeAmount": "£404" }
SDK passes to handler:        CreateClaimData { propertyAddress: {...}, feeAmount: "£404" }
```

**Mid-event callbacks** follow the same pattern: strip prefix inbound, add prefix outbound.
