package uk.gov.hmcts.ccd.sdk.bundling.pdf;

/**
 * A marker for the standard visible page rendered in the position of an expected section that
 * contains no documents at generation time. The rendering pipeline materialises one of these when
 * a section's policy is {@link uk.gov.hmcts.ccd.sdk.bundling.api.EmptySectionPolicy
 * #INCLUDE_PLACEHOLDER}; the page participates in the table of contents, bookmarks and pagination
 * like any other document, and its inclusion is reported as a
 * {@link uk.gov.hmcts.ccd.sdk.bundling.api.BundleWarning}.
 */
public record EmptySectionPage() implements AssemblyContent {
}
