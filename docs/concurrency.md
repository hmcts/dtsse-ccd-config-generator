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

## Non-concurrent events

Legacy callback events declared with `event(...)` are non-concurrent by default; they will be rejected with a `409 conflict` if **any** event has committed in between their start and submission.

Decentralised events are concurrent by default; they will not be blocked by events that commit between their start and submissoin.

However, they may still opt into a case-wide optimistic lock:

```java
configBuilder
    .decentralisedEvent("amendFlags", this::submit)
    .forAllStates()
    .nonConcurrent();
```

In the above example `amendFlags` will be rejected and return an HTTP 409 if **any** event has committed to the case since `amendFlags` was started.

Your own custom events that are concurrent-safe, eg. using inserts and merges, may still modify case flags and links and can remain concurrent.

Mark an event non-concurrent when its submit handler writes potentially stale values, such as a collection edited in XUI and updated as a value eg. CCD/XUI's case flag and link management events.
