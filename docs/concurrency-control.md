Concurrency control

## Goals


* Preserve CCD’s behavior for the legacy case JSON blob.
  * We keep the global optimistic lock and reject conflicting blob updates exactly as CCD does today.
* Conflict avoidance
  * Tables such as case notes can be written independently, using domain‑appropriate concurrency controls.

* Concurrency but not parallelism
    * Case events are serialized (run in order) under a case level lock
    * This ensures an atomic, ordered history.

## Global optimistic lock for the legacy JSON blob (unchanged from CCD)

What it protects: the legacy JSON blob on the case record.

On write, the incoming version is compared to the current one. If the blob has changed since the client read it, the update is rejected (a 409 Conflict is returned), matching CCD’s behavior.

A refinement is that the version is only incremented if the JSON blob actually changes

## Concurrency, not parallelism: serialized case events

We allow concurrency across requests but do not execute parallel writes for the same case. All state‑changing operations are executed as case events under a per‑case lock.

(Note that this is a tightening of CCD's event model which allows multiple event submissions to run in parallel, only one of which will commit.)

Atomic history: each event commits as a whole, and the case event log is ordered. Readers get a coherent, monotonic history (“what happened, and in what order”), regardless of which tables an event touched.

If, for example, a blob update and a case note insertion were to race, one acquires the case lock first. The other waits, then runs, and both succeed. The event log reflects the order they committed.

