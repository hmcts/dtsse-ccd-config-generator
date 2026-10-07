package uk.gov.hmcts.ccd.sdk.bundling.pdf;

public sealed interface AssemblyContent permits PdfSource, MediaLinkPage, EmptySectionPage,
    MissingDocumentPage {
}
