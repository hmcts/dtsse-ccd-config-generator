_Concurrency control

## Goals


* Preserve CCD’s existing global optimistic lock around the legacy case_data JSON blob.
* Concurrent event submissions are now achievable for service data managed outside of this blob


## Unchanged: Global optimistic lock on the case_data JSON blob

As now, concurrent modifications to the case_data blob are prevented via an optimistic locking strategy; stale events will be rejected with an HTTP 409 Conflict.

A refinement is that the case_data version number is only incremented if the JSON blob actually changes, providing the foundation for the next section.

## New: Concurrent events

It is now possible to build events that submit concurrently, eg. enabling multiple parties to upload evidence at the same time, or staff to add case notes without conflicts.

Such scenarios are possible by managing portions of case data outside of the case_data json blob using an appropriate concurrency model (eg. dedicated tables and INSERTs.)

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

### Concurrency (but not parallelism)

All case events execute under a case-level lock wrapped in a database transaction.

Consequently readers still get a coherent, monotonic history (“what happened, and in what order”), regardless of which tables an event touched.

If, for example, a blob update and a case note insertion were to race, one acquires the case lock first. The other waits, then runs, and both succeed. The event log reflects the order they committed.

(Note that this is a tightening of CCD's event model which allows multiple event submissions to run in parallel, only one of which will commit.)

