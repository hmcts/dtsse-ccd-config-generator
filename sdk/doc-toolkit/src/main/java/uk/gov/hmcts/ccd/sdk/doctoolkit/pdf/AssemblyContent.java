package uk.gov.hmcts.ccd.sdk.doctoolkit.pdf;

public sealed interface AssemblyContent permits PdfSource, EmptySectionPage,
    MissingDocumentPage {
}
