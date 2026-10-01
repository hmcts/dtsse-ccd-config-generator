/**
 * The rendering pipeline: orchestration and validation between the public api package and the
 * pdf assembly layer. {@code DefaultBundleRenderer} validates, resolves and spools sources, fails
 * fast on unresolved documents, detects media types from content, converts through the handler
 * registry, assembles, and hands the finished PDF back under a bounded concurrency permit.
 */
package uk.gov.hmcts.ccd.sdk.bundling.render;
