# Isolated DTO Events

Decentralised events can define their own DTO class instead of sharing a case-wide data class. Each DTO-backed event
owns a focused input model containing only the fields it needs.

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

## Declaring a DTO event

DTO-backed decentralised events now require an explicit `fieldNamespace`:

```java
builder.decentralisedEvent(
        "createPossessionClaim",
        CreateClaimData.class,
        "claim.create",
        this::submit,
        this::start
    )
    .initialState(State.AWAITING_FURTHER_CLAIM_DETAILS)
    .name("Make a claim")
    .grant(Permission.CRUD, UserRole.PCS_SOLICITOR);
```

Legacy non-DTO decentralised events remain unchanged:

```java
builder.decentralisedEvent("createPossessionClaim", this::submit, this::start);
```

## Handler behaviour

Your handlers receive the DTO directly:

```java
private CreateClaimData start(EventPayload<CreateClaimData, State> payload) {
    return CreateClaimData.builder()
        .feeAmount("£404")
        .build();
}

private SubmitResponse<State> submit(EventPayload<CreateClaimData, State> payload) {
    CreateClaimData data = payload.caseData();
    caseService.createCase(payload.caseReference(), data.getPropertyAddress(), ...);
    return SubmitResponse.of(State.AWAITING_HEARING);
}
```

DTOs are event-scoped. CCD renders them for the event, submits them back to your handlers, and does not persist them
for you. Your application remains responsible for persisting any data it needs.

## Field namespace rules

`fieldNamespace` is mandatory for DTO-backed decentralised events.

Allowed syntax:

- lowercase dot-separated segments only
- regex `^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*$`

Examples:

- `claim.create`
- `claim.resume`
- `note.add`
- `citizen.application.update`

The SDK converts `fieldNamespace` to a CCD prefix stem using lower camel case:

- `claim.create` -> `claimCreate`
- `note.add` -> `noteAdd`
- `citizen.application.update` -> `citizenApplicationUpdate`

Generated CCD field IDs follow:

```text
ccdFieldId = prefixStem + Capitalize(dtoFieldName)
```

Example:

- namespace `claim.create`
- DTO field `propertyAddress`
- CCD field ID `claimCreatePropertyAddress`

The generator fails fast when:

- namespace is missing
- namespace syntax is invalid
- two DTO events in the same case type produce the same prefix stem
- a generated CCD field ID exceeds CCD's 70 character limit

## Page DSL and field references

Page field bindings still use method references:

```java
.mandatory(CreateClaimData::getPropertyAddress)
.optional(CreateClaimData::getFeeAmount)
```

For simple DTO field references in page conditions and label interpolation, write the DTO field names, not the
prefixed CCD IDs:

```java
.showCondition("showCrossBorderPage=\"Yes\"")
.label("info", "The claim fee is ${feeAmount}.")
```

The SDK rewrites these simple references to the generated CCD field IDs for transport and config generation. The
supported scope in this change is:

- `${fieldName}` label interpolation
- simple show conditions that reference DTO field names

No general CCD expression parser rewrite is attempted here.

## Supported DTO field types

DTO fields must be types that the existing CCD field generator can render. Supported shapes include:

- primitives and boxed primitives
- `String`
- `LocalDate`
- `LocalDateTime`
- enums
- SDK built-in renderable types such as `AddressUK`, `Document`, `DynamicList`, `YesOrNo`
- collections of the above

The generator rejects DTOs that use:

- `@JsonUnwrapped`
- arbitrary nested POJOs or custom persistent case-data models
- `Map`
- unsupported collection element types

Validation is recursive for collection element types.

## Access control

DTO-backed events own their fields exclusively. Access control stays on the event:

```java
.grant(Permission.CRUD, UserRole.PCS_SOLICITOR)
```

If a role can run the event, it gets CRUD access to that event's fields. You do not need per-field `@Access`
configuration for isolated DTO fields.

## Migration

1. Identify the fields an event actually needs.
2. Create a focused DTO for those fields.
3. Switch to `decentralisedEvent(id, DtoClass.class, fieldNamespace, submit, start)`.
4. Update handlers to use `EventPayload<DtoClass, State>`.
5. Keep page field bindings as method references.
6. For simple conditions and `${...}` labels, use DTO field names and let the SDK rewrite them.
7. Remove no-longer-needed event-only fields from the shared case data class.

## TypeScript bindings

When TS bindings are enabled, DTO-backed decentralised events also generate:

- `dto-types.ts`
- `event-contracts.ts`
- `index.ts`

Consumers then compose those bindings with `@hmcts/ccd-event-runtime`. See
[ts-bindings.md](./ts-bindings.md).
