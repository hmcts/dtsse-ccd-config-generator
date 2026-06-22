# Elasticsearch indexing

CCD's search APIs are powered by Elasticsearch and handle search for all professional users amongst other clients.

Postgres remains the source of truth for case data for both centralised and decentralised services.

Consequently the correct, timely & robust indexing of case data from postgres into elasticsearch is critically important.

## Correctness

No lost updates; old case data must never overwrite newer.

(We may skip intermediate versions eg. jumping from version N to N + 2 and skipping N+1 is acceptable).

## Robustness

The indexing process must gracefully handle transient failures including:

1. Network failures & timeouts
2. Crashes, poor performance & unavailability of postgres, the indexing process & elasticsearch

Indexing must automatically handle such failures & recover to a consistent state whereby all committed, indexable postgres changes are replicated to elasticsearch - loss of any such updates is unacceptable.

Unrecoverable failure of the indexing process itself must trigger incident response.

## Unindexable cases

Postgres allows certain types of changes that Elasticsearch does not eg. changing the type of an existing field.

This creates the possibility of desynchronisation whereby one or more cases in postgres can no longer be indexed by elasticsearch.

The indexing process must

1. Alert the service team responsible for the case data in question upon any unindexable cases
2. Provide clear diagnostic information about the root cause of the unindexable case
3. Allow re-indexing once the underlying incompatibility is remediated by the service team
    1. Without requiring platform level support
5. Unindexable cases must not block or impede the continued indexing of indexable cases

## Performance

The indexing process should be as near real time as possible.

It must also be non-blocking: the indexing process should not block case updates and vice versa.

## Historical approaches

### Timestamp high watermarking

An original indexing strategy was based on a [timestamp high watermark strategy](https://www.elastic.co/blog/how-to-keep-elasticsearch-synchronized-with-a-relational-database-using-logstash).

However this suffers a significant correctness issue in that row timestamp order does not necessarily match transaction commit visibility order in a relational database.

A transaction may assign an older modification_time, remain uncommitted, and only become visible after another transaction with a newer timestamp has already advanced the indexing watermark. In that case, the older row can appear behind the watermark and be skipped permanently.

### Dedicated tracking column

A follow on strategy involved the use of a [dedicated column](https://github.com/hmcts/ccd-data-store-api/blob/446e5112613c5d8b534f20eda876f42f9226de4d/src/main/resources/db/migration/V0001__Base_version.sql#L74) on the main ccd case data row, flipped by a database trigger upon case data changes.

This column is then flipped back during the indexing process [by logstash](https://github.com/hmcts/cnp-flux-config/blob/master/apps/ccd/ccd-logstash/ccd-logstash.yaml#L78) but _before_ elasticsearch has accepted the write.

This suffers from a number of issues:

1. Cases are marked as indexed in the database before they are actually indexed into elasticsearch
    1. Failure of logstash (eg. a crash or forced termination) can result in lost updates
2. Changes to both case data and the indexing process must modify this column, resulting in significant lock contention and deadlocks; a delay in locking a single row can hold locks on an entire batch.
3. booleans are not good candidates for database indexes; their low cardinality gives low selectivity and index bloat

### Dead letter queues

Logstash itself handles unindexable cases by adding them to a 'dead letter queue' on its local filesystem, which we further configure logstash to write to a dedicated index.

However the querying and monitoring of this shared index has been cumbersome for service teams.

### Logstash limitations

Given that:

1. The officially recommended logstash RDBMS timestamp-watermark approach does not meet our correctness requirements
2. A dedicated tracking column can still lose updates if logstash fails (and introduces performance problems)

a custom approach is warranted.

## ccd-runtime-indexing

This custom indexing component uses postgres to track all indexing state including the dead letter queue - as opposed to logstash container filesystems & dedicated elasticsearch indexes.

This enables us to meet the correctness requirements above while improving observability to the owning service team by consolidating all state in a single database.

It must be delivered in a backwards compatible way to existing consumers; eg. sptribs-logstash must continue to function until migration.

### Case views as source of truth

A key goal of decentralised data persistence is to decouple a service's domain model and private persistence schema from CCD's case-data JSON - which becomes an API based projection.

Services may persist their authoritative state however they choose, including in arbitrary private tables. They translate that state into the CCD JSON shape defined by their CCD definitions on read via CaseView, and update their private state on write via event handlers.

Consequently each case type's CaseView provides the authoritative CCD JSON projection that is captured for indexing.

During event submission:

1. A committed, non-idempotent event always advances the case revision, yielding R
2. The physical ccd.case_data.data blob is updated only when the event changes that blob; decentralised services may keep their authoritative state elsewhere
3. ccd.es_queue records (reference, case_revision) = (case reference, R) in the same transaction
4. The case type's CaseView is invoked to render the saved case to CCD JSON, yielding V
5. ccd.case_event stores that immutable event snapshot:
   1. ccd.case_event.data = V
   2. ccd.case_event.case_revision = R
