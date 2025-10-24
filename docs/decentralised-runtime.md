# Decentralised Runtime

The decentralised runtime implements CCD’s new decentralised persistence APIs with opinionated infrastructure so service teams can
shift case ownership without rebuilding everything from scratch.
out of the box.

## Case views

Services can plug in a [`CaseView<ViewType, StateEnum>`](../sdk/decentralised-runtime/src/main/java/uk/gov/hmcts/ccd/sdk/CaseView.java) implementation.

Case views are invoked when CCD needs to load case data from your service and constitute the API your service exposes to CCD for reading case data.

**How** services structures its data is now an implementation detail; It could **be** a JSON blob, **have** a JSON blob or be fully structured.

Case views can also be used to inject dynamically rendered HTML & markdown at runtime, avoiding the need to store it in the database.

## Database schema

Flyway migrations under [`sdk/decentralised-runtime/src/main/resources/dataruntime-db`](../sdk/decentralised-runtime/src/main/resources/dataruntime-db)
provision a dedicated `ccd` schema within your application with the structures needed to mirror existing CCD behaviour under the decentralised model:

- `case_data` mirrors ccd's case_data table
- `case_event` mirrors ccd's case_event table
- `es_queue` tracks cases that require elasticsearch indexing
- `message_queue_candidates` mirrors CCD’s Service Bus transational outbox table. Published events are written here.


## Idempotency

The SDK implements the required idempotency model of CCD's persistence API.

If an incoming idempotency key has already been processed the runtime replays the stored response; otherwise the request continues and the idempotency key is persisted alongside
the new event.

## Transaction control

The SDK wraps every case event inside a database transaction covering:

>- idempotency check & case-level lock acquisition
>- Invocation of AboutToSubmit callback (if defined)
>- upsert of ccd.case_data,
>- insert into ccd.case_event (audit history)
>- insert into Elasticsearch queue table

If a concurrent update to ccd.case_data.data is detected a `409 CONFLICT` is returned and the transaction rolls back, aligning behaviour with CCD.

## Elasticsearch indexing

The SDK maintains a queue of cases requiring Elasticsearch indexing in `ccd.es_queue`.

## Supplementary data

Supplementary data operations are implemented and persisted in the ccd.case_data table.

## Message publishing to Azure Servicebus

A transactional-outbox based message_queue_candidates table is maintained and written to based upon your ccd definition, mirroring CCD's implementation.
