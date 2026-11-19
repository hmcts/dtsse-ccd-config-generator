/**
 * The rendering pipeline: orchestration and validation between the public api package and the
 * pdf assembly layer. {@code DefaultBundleRenderer} validates, resolves and spools sources, fails
 * fast on unresolved documents, converts each one to PDF through document-conversion's
 * {@code DocumentConverter} (which checks media types against content), assembles, and hands the
 * finished PDF back under a bounded concurrency permit.
 */
package uk.gov.hmcts.ccd.sdk.bundling.render;
