# System events

Services can perform system-initiated changes locally without a round trip through CCD's API, while
remaining transactional and [auditable](./audit.md).

This covers decentralised case data projected from application-owned relational data.

> **Warning:** Before using this API, you must read and understand the
> [security model](#security-boundary).

## Example

A service can make a local case change and record it as a system event in one operation:

```java
String serviceRequestReference = paymentStatusCallback.getServiceRequestReference();

systemEventExecutor.execute(caseReference, idempotencyKey, () -> {
    paymentService.applyUpdate(caseReference, serviceRequestReference);
    return new SystemEventResult<>(
        "paymentUpdated",
        "Payment updated",
        "Payment updated",
        Optional.of(State.CASE_ISSUED)
    );
});
```

System events reuse the transaction and persistence stages described in the runtime's
[event submission flow](./decentralised-runtime.md#event-submission-flow).

In this example:

1. The runtime opens a transaction, locks the case and checks the idempotency key.
2. For a new event, it executes the lambda inside the transaction.
3. When the lambda returns successfully, it projects the case and writes a `ccd.case_event` record
   with the event ID `paymentUpdated`.
4. If the lambda or subsequent persistence fails, it rolls back the transaction.

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

The executor bean is only created when `id` is configured. If it is present, all four values are
required and are validated when the application starts.

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
    () -> {
        caseAccessService.applyChange(caseReference, request);
        return new SystemEventResult<>(
            "caseAccessUpdated",
            "Case access updated",
            "Case access updated",
            Optional.empty()
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

Side effects work should therefore be idempotent or moved outside the short local event transaction. Where
the remote action must follow a committed case change, it should be driven by an outbox or another
post-commit mechanism.
