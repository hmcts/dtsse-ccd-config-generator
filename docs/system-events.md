# System events

Services can perform system-initiated changes locally without a round trip through CCD's API, while
remaining transactional and [auditable](./audit.md).

This covers decentralised case data projected from application-owned relational data.

System events can only operate on existing CCD cases. New cases must be created through CCD's case
creation API before this executor can be used.

> **Warning:** Before using this API, you must read and understand the
> [security model](#security-boundary).

## Example

A service can make a local case change and record it as a system event in one operation:

```java
String serviceRequestReference = paymentStatusCallback.getServiceRequestReference();

SystemEventExecutionResult execution = systemEventExecutor.execute(caseReference, idempotencyKey, context -> {
    paymentService.applyUpdate(context.caseReference(), serviceRequestReference);
    return SystemEventResult.withStateTransition(
        "paymentUpdated",
        "Payment updated",
        "Payment updated",
        State.CASE_ISSUED
    );
});
```

The result contains the persisted event ID and an `EXECUTED` or `REPLAYED` outcome. A replay is a
successful idempotent result: the original event ID is returned and the action is not invoked again.
For a new event, the action receives the case reference, idempotency key, case type ID and current
state read while the case is locked. This also allows an action method with the same signature to be
passed by method reference.

System events reuse the transaction and persistence stages described in the runtime's
[event submission flow](./decentralised-runtime.md#event-submission-flow).

In this example:

1. The runtime opens a transaction, locks the case and checks the idempotency key.
2. For a new event, it executes the lambda inside the transaction.
3. When the lambda returns successfully, it projects the case and writes a `ccd.case_event` record
   with the event ID `paymentUpdated`.
4. If the lambda or subsequent persistence fails, it rolls back the transaction.

`SystemEventExecutor.execute(...)` must be called without an active transaction. This ensures the
executor creates and owns the complete case-event transaction rather than joining a caller's transaction.

## System user identity

The SDK's system user identity is set through Spring Boot configuration:

```yaml
ccd:
  decentralised-runtime:
    system-user:
      id: 00000000-0000-0000-0000-000000000000
      username: case-service-system
      first-name: Case Service
      last-name: System
```

The executor bean is only created when `id` or `password` is configured. If `id` is present, all four
values are required and are validated when the application starts.

### Look the system user up from IDAM

A service that already holds the system user's IDAM credentials can configure those instead of
storing the user's ID separately:

```yaml
ccd:
  decentralised-runtime:
    system-user:
      username: ${SYSTEM_USER}
      password: ${SYSTEM_USER_PASSWORD}
      first-name: Case Service   # optional; defaults to the IDAM given name
      last-name: System          # optional; defaults to the IDAM family name
```

With no `id`, `username` and `password` are required at startup, but IDAM is not called then. The
first `execute(...)` call signs in as the system user, reads its `uid` and names from IDAM's
`/o/userinfo`, and caches them for the life of the application. The lookup runs before the case
transaction opens, so it never holds the case lock. Only one lookup runs at a time: a system event
that arrives while one is running waits up to five seconds for it, then fails rather than queueing
behind a slow IDAM.

A failed lookup is remembered, so system events fail fast rather than each signing in again:

- If IDAM rejects the sign-in (400, 401 or 403), returns no token or a user with no ID, or returns
  a user with no given or family name when `first-name` or `last-name` isn't configured, the
  lookup is never retried. Every system event fails until the configuration is fixed and the
  application restarted. This stops a wrong password from locking the shared system account.
- Any other failure, such as IDAM being unavailable, is retried on the first call after one
  minute.

A configured `id` takes precedence. A blank `id` or `password`, such as an unset `${VAR:}`
placeholder, counts as not configured.

For work performed solely by the service, the configured system identity is recorded as the event
user. When an `ActorAttribution` is supplied, the actor is recorded as the event user and the system
identity is recorded in the `proxied_by` fields. This matches the existing CCD history model for one
identity acting on behalf of another.

### Attribute system work to a person

```java
systemEventExecutor.execute(
    caseReference,
    new ActorAttribution(userId, firstName, lastName),
    accessChangeIdempotencyKey,
    context -> {
        caseAccessService.applyChange(context.caseReference(), request);
        return SystemEventResult.withoutStateTransition(
            "caseAccessUpdated",
            "Case access updated",
            "Case access updated"
        );
    }
);
```

The caller must derive a stable idempotency key from the originating operation and reuse it for every
retry. Replaying the same key for the same case returns without invoking the action again.

System event IDs do not have to be registered in CCD configuration. The result supplies the event ID,
display name and optional summary for the audit entry. CCD's public history endpoint applies configured
event access rules, so an unregistered ID remains in persisted history but is not returned by CCD.

## Security boundary

`SystemEventExecutor` is a privileged in-process API, not an authentication or authorisation
mechanism. Calling it asserts that the application has already established the trustworthiness of
the request and authorised the specific case change.

The caller is responsible for:

- authenticating the source, such as an incoming user, service callback or scheduled task;
- authorising that source to perform the requested change on the case;
- validating the input and enforcing the domain rules for any returned state transition; and
- deriving `ActorAttribution` only from a trusted identity.

`ActorAttribution` is audit information. Supplying an actor does not authenticate that person or
grant them permission to change the case. An untrusted request value must not be copied directly into
the attribution fields without first validating the corresponding identity.

The executor installs the configured system identity only so the authorised change can be persisted
and attributed consistently. It does not validate an incoming token, apply configured CCD event
permissions or pre-states, or make an otherwise unauthorised request safe.

## Appropriate uses

The boundary is useful for durable case-model changes initiated outside a configured CCD event,
including:

- access changes initiated by a controller or background task;
- external callbacks which update the case;
- generated document attachment;
- generated credentials or reference data belonging to the case; and
- recording an external service request against the case.

## Transaction boundary and external effects

The executor can make local database changes atomic. It cannot make external APIs, document storage,
notifications or task-management systems participate in that database transaction.

Work involving side effects should therefore be idempotent or moved outside the short local event transaction. Where
the remote action must follow a committed case change, it should be driven by an outbox or another
post-commit mechanism.
