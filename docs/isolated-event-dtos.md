# Isolated DTO Events

This proposal adds a new overload of `decentralisedEvent` that lets events define their own DTO class instead of
sharing a case-wide data class. Each DTO-backed event owns a focused input model containing only the fields it needs.

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

A new overload accepts the DTO class alongside the event ID and handlers:

```java
builder.decentralisedEvent(
        "createPossessionClaim",
        CreateClaimData.class,
        this::submit,
        this::start
    )
    .initialState(State.AWAITING_FURTHER_CLAIM_DETAILS)
    .name("Make a claim")
    .grant(Permission.CRUD, UserRole.PCS_SOLICITOR);
```

Existing non-DTO decentralised events are unaffected:

```java
builder.decentralisedEvent("createPossessionClaim", this::submit, this::start);
```

## Handler behaviour

Handlers receive the DTO directly:

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

DTOs are event-scoped and ephemeral. The decentralised runtime does not persist DTO payloads — neither in CCD nor in
any database. The payload exists only for the lifetime of the event flow (start → mid-event callbacks → submit). Your
submit handler is responsible for persisting any data it needs into your own data store.

## Payload transport

DTO-backed events use a single opaque `payload` CCD field. The SDK serialises the DTO to a JSON string and stores it
in this field. On submission, the SDK deserialises the JSON string back into the DTO before passing it to your handler.

CCD does not need to understand the structure of the payload. Individual DTO fields are not mapped to individual CCD
fields — there is no per-field prefixing, no generated CCD field IDs, and no field length constraints beyond the
capacity of the payload field itself.

### Searchable fields

Fields that need to be searchable through CCD are declared separately as explicit CCD fields, independent of the DTO
payload. The DTO payload itself is not searchable through CCD.

### Auditing

CCD does not introspect the payload field. Auditing of DTO contents is handled by your backend service.

## Concurrency

Because DTO payloads are ephemeral, data hydrated onto the DTO by about-to-start or mid-event callbacks reflects a
point-in-time snapshot. Another user or process may modify the underlying data between the moment your callback reads it
and the moment the user submits.

Your application is responsible for ensuring that event handlers behave correctly under concurrency — for example,
through appropriate locking, idempotent submissions, or re-reading authoritative state at submit time rather than
trusting values hydrated at start.

## No CCD UI configuration

DTO-backed decentralised events do not use CCD's UI configuration. There are no pages, show conditions, label
interpolations, or field display options. Your service provides its own frontend (e.g. GOV.UK Nunjucks templates) which
works directly with the DTO structure. CCD is not involved in rendering.

The page DSL (`.mandatory()`, `.optional()`, `.showCondition()`, `.label()`, etc.) is not available on the DTO event
builder. Conditional rendering and field layout are your frontend's responsibility.

## Supported DTO field types

DTOs are serialised as JSON using Jackson. Any type that Jackson can serialise and deserialise is supported, including
nested objects, maps, and collections. There are no CCD field type restrictions since CCD never inspects the payload.

## Access control

DTO-backed events own their payload exclusively. Access control stays on the event:

```java
.grant(Permission.CRUD, UserRole.PCS_SOLICITOR)
```

If a role can run the event, it gets CRUD access to that event's payload. You do not need per-field `@Access`
configuration for isolated DTO fields.

## Migration from shared case data

1. Identify the fields an event actually needs.
2. Create a focused DTO for those fields.
3. Use `decentralisedEvent(id, DtoClass.class, submit, start)`.
4. Update handlers to use `EventPayload<DtoClass, State>`.
5. Remove no-longer-needed event-only fields from the shared case data class.

## Typed frontend–backend contract

A key benefit of DTO events is a single source of truth for the data contract between your Java backend and your
frontend. The Java DTO class defines the shape, and the SDK generates TypeScript interfaces from it. Your backend
handlers and your frontend templates both work against the same type — changes to the DTO fail the build on both sides
if they fall out of sync.

When TS bindings are enabled, the SDK generates:

- `dto-types.ts` — TypeScript interfaces and enums mirroring your Java DTOs
- `event-contracts.ts` — an event manifest mapping event IDs to their DTO types

Your frontend imports these generated types and composes them with `@hmcts/ccd-event-runtime`, which provides a typed
client for starting events, validating mid-event, and submitting. The runtime handles CCD transport so your frontend
code works with plain DTO-shaped objects — never raw CCD payloads.

```ts
import { createCcdClient } from '@hmcts/ccd-event-runtime';
import { caseBindings, type CreateClaimData } from '../generated/ccd/MY_CASE_TYPE';

const client = createCcdClient(config, caseBindings);
const flow = await client.event('createPossessionClaim').start();

const data: CreateClaimData = flow.data;
data.feeAmount = '£404';
await flow.submit(data);
```

For full configuration and mid-event validation details, see [ts-bindings.md](./ts-bindings.md).
