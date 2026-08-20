package uk.gov.hmcts.ccd.sdk.bundling.pdf;

/**
 * What an {@link AssemblyItem} renders as: an already-converted source PDF, a generated media
 * link page, or a generated empty-section placeholder page.
 */
public sealed interface AssemblyContent permits PdfSource, MediaLinkPage, EmptySectionPage {
}
