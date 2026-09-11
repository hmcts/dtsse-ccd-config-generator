## Goals

* Preserve CCD’s global optimistic lock around the existing case_data JSON blobs.
* Facilitate alternative concurrency models for service-managed data.


## Unchanged: Global optimistic lock on the case_data JSON blob

As now, concurrent modifications to the case_data blob are prevented via an optimistic locking strategy; stale events will be rejected with an HTTP 409 Conflict.

A refinement is that the case_data version number is only incremented if the JSON blob actually changes, providing the foundation for the next section.

## New: Concurrent events

It is now possible to implement events that submit concurrently, i.e. enabling multiple parties to upload evidence at the same time or staff to add case notes without causing conflicts for other users working on the case.

Such scenarios are possible by managing portions of case data _outside_ of the case_data JSON blob using an appropriate concurrency model (e.g. dedicated tables and INSERT statements).

Such events must necessarily avoid modifying the case_data blob or 409 conflicts will continue to arise.

```mermaid
erDiagram
  CASE_DATA ||--o{ CASE_NOTES : "INSERT into case_notes does not increment case_data version"

  CASE_DATA {
    bigint reference PK
    jsonb  json_blob
    bigint version "Used for optimistic locking on the blob"
  }
  CASE_NOTES {
    bigint id PK
    bigint case_reference FK
    text   body
    timestamptz created_at
  }
```

### Concurrency — but not parallelism

All case events execute under a case-level lock wrapped in a database transaction.

Viewers still get a coherent, monotonic history (“what happened, and in what order”), regardless of which tables an event touched.

If, for example, a blob update and a case note insertion were to race, one acquires the case lock first. The other waits, then runs, and both succeed. The event log reflects the order they committed and accurately reflects the changes each made.

Note that this is a tightening of CCD's current implementation which allows multiple event submissions to run in parallel, only one of which will commit.

## Selective conflicts between service-managed events

Service-managed events can opt into a named concurrency group when their data model requires mutually exclusive updates:

```java
configBuilder
    .decentralisedEvent("addCaseLink", this::submit)
    .forAllStates()
    .concurrencyGroup("case-links");
```

An event may belong to more than one group. Group names are scoped to a case type, and every event in a group conflicts with every other event in that group, including another instance of itself.

When a grouped event is submitted, the decentralised runtime compares the revision on which the event was started with the current case revision. It examines the committed events in the revision range `(startRevision, currentRevision]`. The submission is rejected with HTTP 409 Conflict if any of those events shares a concurrency group with it. Intervening events outside its groups do not prevent submission.

The runtime expands group membership to concrete conflicting event IDs before entering the transaction. After acquiring the
case-level lock, the event guard checks idempotency and scans the revision range in one database round trip. Idempotency
replay takes precedence over a conflict. Consequently, two grouped submissions that race from the same revision cannot
both commit, while replaying an already committed request remains safe. A grouped submission with a missing or invalid
start revision is also rejected with HTTP 409 rather than bypassing the check.

Concurrency groups are only valid for events on existing cases; they cannot be configured on case-creation events. Events with no concurrency group retain the behaviour described above.
