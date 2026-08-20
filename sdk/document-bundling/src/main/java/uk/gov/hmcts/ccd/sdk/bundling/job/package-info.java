/**
 * The optional durable job runner: a transactional outbox in the consuming service's own
 * database, following {@code sdk/task-management}, that persists bundle requests and invokes the
 * same rendering engine. Consumers with their own reliable job mechanism may call the
 * {@code BundleRenderer} directly; the outbox is never mandatory.
 */
package uk.gov.hmcts.ccd.sdk.bundling.job;
