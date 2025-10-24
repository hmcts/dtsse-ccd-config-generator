# Decentralised Data Persistence — Conceptual Introduction

TL;DR

* You can store case data in a service‑owned database rather than CCD’s case_data table.

* No big bang rewrite: your application can remain 'callback' driven (AboutToStart / MidEvent / AboutToSubmit / Submitted).

* The same JSON blob flows through callbacks; the difference is where and how it is persisted

* Service teams can own their data, make effective use of databases and evolve at their own pace.
* Updates to this SDK can help with onboarding

## As-is: centralised persistence

CCD orchestrates events and invokes service callbacks.

Each callback receives the full case JSON blob, performs validation, mutation, and side effects (emails, integrations, etc.), then returns a (possibly) changed blob to CCD for persistence.

CCD persists this blob verbatim in its case_data table.

```mermaid
sequenceDiagram
autonumber
participant UI as UI
participant CCD
participant Svc as Service

    UI->>CCD: Trigger Event (caseId, event)
    CCD->>Svc: POST callback(JSON)
    Svc-->>CCD: Return JSON' (validated/mutated)
    CCD-->>UI: Next state / response
```

## The decentralised option

CCD continues to orchestrates events and invokes service callbacks, but the service becomes the source of truth for case data.


```mermaid
sequenceDiagram
autonumber
participant UI as UI
participant CCD
participant Svc as Service
participant SDB as Service DB

    UI->>CCD: Trigger Event (caseId, event)
    CCD->>Svc: Read current case_data
    CCD->>Svc: POST /submit (JSON)
    Note right of Svc: Validate & mutate JSON<br/>Side effects (emails, integrations)
    Svc->>SDB: Save case_data = ... 
    Svc-->>CCD: Return JSON' (for immediate UI/state)
    CCD-->>UI: response
```
