# Decentralised Runtime

The decentralised runtime implements CCD’s decentralised persistence APIs with opinionated infrastructure so service
teams can shift case ownership without rebuilding everything from scratch.

## Onboarding

Opt in via the Gradle `ccd {}` block:

```groovy
ccd {
  configDir = file('build/definitions')
  decentralised = true
  // optional: runtimeIndexing = true // pulls in the SDK Elasticsearch indexer
}
```

Setting `decentralised = true` brings in the runtime, applies the database migrations and flips the SDK into
decentralised mode. Without this flag the build continues to target CCD’s centralised persistence.

## Case views

Services must provide a [`CaseView<ViewType, StateEnum>`](../sdk/decentralised-runtime/src/main/java/uk/gov/hmcts/ccd/sdk/CaseView.java)
implementation. CCD invokes the view whenever it needs to read case data, so this becomes the API surface your service
exposes back to CCD.

**How** your service structures the data is now an implementation detail; it could be a JSON blob, enrich the blob, or be
fully structured.

Case views can also inject dynamically rendered HTML/Markdown at runtime, avoiding the need to store presentation
fragments in the database.

> **Mandatory:** decentralised services must expose exactly one Spring-managed `CaseView` bean. The application fails fast
> at startup if no view is registered.

## Database schema

Flyway migrations under [`sdk/decentralised-runtime/src/main/resources/dataruntime-db`](../sdk/decentralised-runtime/src/main/resources/dataruntime-db)
provision a dedicated `ccd` schema within your application with the structures needed to mirror existing CCD behaviour under the decentralised model:

- `case_data` mirrors CCD’s `case_data` table, including metadata such as state, security classification, TTL and the JSON payload.
- `case_event` mirrors CCD’s `case_event` table and adds an idempotency key
- `es_queue` tracks cases that require Elasticsearch indexing 
- `message_queue_candidates` mirrors CCD’s Service Bus transactional outbox table.

## Elasticsearch indexing

The SDK maintains a queue of cases requiring Elasticsearch indexing in `ccd.es_queue`.

## Idempotency

The SDK implements the required idempotency model of CCD's persistence API.

If an incoming idempotency key has already been processed the runtime replays the stored response; otherwise the request continues and the ide
mpotency key is persisted alongside
-the new event.

## Transaction control

The SDK wraps every case event inside a database transaction covering:

- idempotency check & case-level lock acquisition
- Invocation of AboutToSubmit callback (if defined)
- upsert of ccd.case_data,
- insert into ccd.case_event (audit history)
- insert into Elasticsearch queue table

The orchestration lives in [`CaseSubmissionService`](../sdk/decentralised-runtime/src/main/java/uk/gov/hmcts/ccd/sdk/CaseSubmissionService.java). If a concurrent update to `ccd.case_data` is detected a `409 CONFLICT` is returned and the transaction rolls back, aligning behaviour with CCD.

## Supplementary data

Supplementary data operations are implemented and persisted in the `ccd.case_data` table via [`SupplementaryDataService`](../sdk/decentralised-runtime/src/main/java/uk/gov/hmcts/ccd/sdk/SupplementaryDataService.java), using PostgreSQL’s JSON functions to apply `$set`/`$inc` style updates atomically.

## Message publishing to Azure Servicebus

A transactional-outbox based `message_queue_candidates` table is maintained and written to based upon your CCD definition, mirroring CCD's implementation.
