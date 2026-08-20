/**
 * Built-in CDAM adapters for the document-bundling module: the resolver for CDAM-sourced inputs
 * and the invariant production destination that publishes the finished bundle to CDAM.
 *
 * <p>Artifact storage is an invariant, not a port decision — production always uploads through
 * {@link uk.gov.hmcts.ccd.sdk.bundling.cdam.CdamBundleDestination}. Both adapters authenticate
 * through {@link uk.gov.hmcts.ccd.sdk.bundling.cdam.BundlingAuthenticationProvider}, the system
 * user port, so background bundling never depends on an end-user token.
 *
 * <p>Memory profile of resolution: {@code ccd-case-document-am-client}'s Feign decoder
 * materialises each binary response as a fully buffered {@code ByteArrayResource}, so one whole
 * document sits on the heap during its fetch — that is unavoidable with this client, and a
 * streaming decode in {@code ccd-case-document-am-client} is the long-term fix. The resolver
 * contains the cost to roughly one document at a time by fetching sequentially and spooling each
 * binary to an owner-only file in its configured spool directory before fetching the next; each
 * resolved document then streams from its spooled file and deletes it on close. The rendering
 * pipeline may copy the spooled file again into its own job-scoped spool — collapsing that
 * double spool is an optimisation seam for the pipeline, not for these adapters.
 */
package uk.gov.hmcts.ccd.sdk.bundling.cdam;
