/**
 * Test and local-run support for the document-bundling module.
 *
 * <p>Nothing in this package is for production use. Production artifact storage is invariant —
 * always CDAM, via {@link uk.gov.hmcts.ccd.sdk.bundling.cdam.CdamBundleDestination} — and these
 * types exist only so consumers can exercise the renderer in their own tests and local runs
 * without a CDAM instance.
 */
package uk.gov.hmcts.ccd.sdk.bundling.testing;
