/**
 * The rendering pipeline: orchestration and validation between the public api package and the
 * pdf assembly layer. {@code DefaultBundleRenderer} implements the nine design steps — validate,
 * resolve and spool, fail fast on unresolved documents, detect media types from content, convert
 * through the handler registry, inspect converted PDFs, assemble, validate and publish, report —
 * with per-stage timings, structured logging, optional Micrometer metrics, a bounded concurrency
 * permit, and a hard end-to-end deadline.
 */
package uk.gov.hmcts.ccd.sdk.bundling.render;
