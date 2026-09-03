# System events

Services can perform system-initiated changes locally without a round trip through CCD's API, while
remaining transactional and audited.

This proposal covers decentralised case data projected from application-owned relational data.

## Example

A service can make a local case change and record it as a system event in one operation:

```java
String serviceRequestReference = paymentStatusCallback.getServiceRequestReference();

systemEventExecutor.execute(
    caseReference,
    new ActorAttribution(userId, firstName, lastName),
    idempotencyKey,
    () -> {
        paymentService.applyUpdate(caseReference, serviceRequestReference);
        return new SystemEventResult<>(
            "paymentUpdated",
            "Payment updated",
            Optional.of(State.CASE_ISSUED)
        );
    }
);
```

## System user identity

The sdk's system user identify is set through Spring Boot configuration:

```yaml
ccd:
  decentralised-runtime:
    system-user:
      id: 00000000-0000-0000-0000-000000000000
      username: case-service-system
      first-name: Case Service
      last-name: System
```

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
            Optional.empty()
        );
    }
);
```
## Proposed API

```java
public interface SystemEventExecutor<State> {

    void execute(
        long caseReference,
        ActorAttribution actor,
        UUID idempotencyKey,
        SystemEventAction<State> action
    );
}

@FunctionalInterface
public interface SystemEventAction<State> {

    SystemEventResult<State> execute();
}

public record SystemEventResult<State>(
    String eventId,
    String summary,
    Optional<State> state
) {
}

public record ActorAttribution(
    String id,
    String firstName,
    String lastName
) {
}
```

The caller must derive a stable idempotency key from the originating operation and reuse it for every
retry.

System event IDs do not have to be registered in CCD configuration. The result supplies the event ID
and summary required by the audit entry.

An application may optionally register descriptive metadata for consistent display names.

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

These are durable changes to the case model which should produce one audited case event and one
refreshed snapshot.

The following should normally remain outside system events:

- notification delivery and outbox rows;
- `scheduled_tasks` rows;
- generation-failure and pack-delivery operational evidence;
- draft or unsubmitted event data;
- test-support data; and
- writes already made by a configured event submit handler.

## Transaction boundary and external effects

The executor can make local database changes atomic. It cannot make external APIs, document storage,
notifications or task-management systems participate in that database transaction.

Remote work should therefore be idempotent or moved outside the short local event transaction. Where
the remote action must follow a committed case change, it should be driven by an outbox or another
post-commit mechanism. Holding a database lock while making a remote call increases contention and
still does not make the remote operation transactional.
