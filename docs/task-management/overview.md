# Overview (Current vs API-First)

API details live in:
- [Task creation](api/task-creation.md)
- [Task termination](api/task-termination.md)

## As-Is Overview (Current WA Flow)

This describes the current CCD → WA task creation path and the components involved.

### Summary
- Task rules live in DMNs under `src/main/resources/dmn/`.
- CCD event publication drives the workflow; the service does not create tasks directly.
- CCD uses a transactional outbox in its database; CCD Message Publisher polls it.
- Camunda orchestrates task creation and holds process state.
- Task Monitor bridges Camunda → Task Management (CFT task DB).
- Case-event-driven task operations (cancel, warn, reconfigure) are routed by Case Event Handler.

```mermaid
sequenceDiagram
  participant CCD as CCD Data Store
  participant CCDDB as CCD DB + Outbox
  participant CMP as CCD Message Publisher
  participant SB as Azure Service Bus
  participant WAEH as WA Case Event Handler
  participant WAWA as WA Workflow API
  participant CAM as Camunda Engine
  participant WATM as WA Task Monitor
  participant WATMGT as WA Task Management API
  participant CFTDB as CFT Task DB

  CCD->>CCDDB: Commit case + outbox
  CMP->>CCDDB: Poll outbox
  CMP->>SB: Publish message
  SB-->>WAEH: Consume event
  WAEH->>WAWA: Evaluate initiation DMN
  WAWA->>CAM: DMN eval
  CAM-->>WAWA: DMN results
  WAWA-->>WAEH: DMN results
  WAEH->>WAWA: createTaskMessage
  WAWA->>CAM: createTaskMessage
  CAM->>CAM: Start BPMN process\n(create unconfigured task)
  WATM->>CAM: Query unconfigured tasks
  WATM->>WATMGT: POST /task/{id}/initiation
  WATMGT->>CAM: Eval config + permissions DMNs
  WATMGT->>CFTDB: Persist task record
```

## Proposed - API-First Tasks

### Goals
- Task rules live in Java (testable code), not DMNs.
- The service creates fully formed tasks via a new Task Management API endpoint.
- Services lose their transitive dependency on Servicebus & Camunda
- Backwards compatibility; existing task management integrations are unaffected
- Testability; services can write end to end automated tests for their task management integration that:
  - Are deterministic
  - Run locally

### Mermaid Diagram
```mermaid
sequenceDiagram
  participant CCD as CCD Data Store
  box "sptribs-case-api"
    participant SVC as sptribs-callbacks
    participant DB as Service DB Outbox
    participant POLL as Outbox Poller
  end
  participant WATMGT as WA Task Management API
  participant CFTDB as CFT Task DB

  CCD-->>SVC: Case event
  SVC->>SVC: Java rules build task payload
  SVC->>DB: Store outbox record (same transaction)
  POLL->>DB: Read pending outbox records
  POLL->>WATMGT: POST /tasks (API-first)
  WATMGT->>CFTDB: Persist task record
  POLL->>DB: Mark outbox record processed
  Note over SVC,WATMGT: On completion/cancel/reconfigure events → Task Management API calls
```
