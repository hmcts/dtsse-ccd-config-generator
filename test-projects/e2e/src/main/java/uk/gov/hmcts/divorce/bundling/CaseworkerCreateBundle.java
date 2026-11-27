package uk.gov.hmcts.divorce.bundling;

import static uk.gov.hmcts.divorce.bundling.BundleFixtures.fixture;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.LEGAL_ADVISOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;

import java.time.LocalDate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.ccd.sdk.doctoolkit.HtmlPage;
import uk.gov.hmcts.ccd.sdk.doctoolkit.MissingDocuments;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PageNumbers;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfPart;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfSection;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

/**
 * The doc toolkit's worked example of a document made once: a decentralised event that builds a {@link PdfRequest}
 * (from classpath fixtures here, CDAM documents in a real service), records the bundle as requested and submits it
 * under a key of its own. The event does not wait: the toolkit renders it in the background, stores it in CDAM on
 * the case and hands it to {@link CaseDocumentsHandler}, which completes the bundle. PDFs pass through, the image and
 * the office document are converted, the recording is a page the service writes in HTML, and the empty section gets
 * a placeholder page.
 */
@Component
public class CaseworkerCreateBundle implements CCDConfig<CaseData, State, UserRole> {

    public static final String CASEWORKER_CREATE_BUNDLE = "caseworker-create-bundle";

    public static final String BUNDLE_TITLE = "Hearing bundle";
    public static final String APPLICATIONS_SECTION = "Applications";
    public static final String EVIDENCE_SECTION = "Evidence";
    public static final String CORRESPONDENCE_SECTION = "Correspondence";
    public static final String POTENTIAL_ENERGY_TITLE = "Potential energy application";
    public static final String MEDICAL_REPORT_TITLE = "Claimant medical report";
    public static final String LOCATION_STATEMENT_TITLE = "Location statement";
    public static final String FLYING_PIG_TITLE = "Flying pig photograph";
    public static final String HEARING_RECORDING_TITLE = "Hearing recording, day 2";
    public static final String HEARING_RECORDING_URL =
        "https://media.example.net/recordings/hearing-day-2.mp3";
    public static final String HEARING_RECORDING_NOTE = "Playback requires case access";

    @Override
    public void configureDecentralised(final DecentralisedConfigBuilder<CaseData, State, UserRole> configBuilder) {
        configBuilder
            .decentralisedEvent(CASEWORKER_CREATE_BUNDLE, this::submit)
            .forAllStates()
            .name("Create hearing bundle")
            .description("Generate the hearing bundle with the SDK's doc toolkit")
            .grant(CREATE_READ_UPDATE, CASE_WORKER, SUPER_USER)
            .grantHistoryOnly(LEGAL_ADVISOR, JUDGE);
    }

    private SubmitResponse<State> submit(EventPayload<CaseData, State> payload) {
        return SubmitResponse.<State>builder()
            .confirmationHeader("Hearing bundle requested")
            .confirmationBody("It will appear on the case once it has been made")
            .build();
    }

    static PdfRequest buildRequest(final long caseReference) {
        return PdfRequest.builder(BUNDLE_TITLE)
            .fileName("case-" + caseReference + "-hearing-bundle.pdf")
            // The court's bundle layout; each feature is switched on.
            .titlePage()
            .tableOfContents()
            .sectionCoverSheets()
            .pageNumbers(PageNumbers.BOTTOM_CENTRE_N_OF_M)
            .confidentialMarking()
            .missingDocuments(MissingDocuments.PLACEHOLDER)
            .section(PdfSection.builder(APPLICATIONS_SECTION)
                .document(PdfPart.builder(fixture(BundleFixtures.POTENTIAL_ENERGY_PDF))
                    .title(POTENTIAL_ENERGY_TITLE)
                    .date(LocalDate.of(2026, 1, 12))
                    .build())
                .document(PdfPart.builder(fixture(BundleFixtures.CLAIMANT_MEDICAL_REPORT_PDF))
                    .title(MEDICAL_REPORT_TITLE)
                    .date(LocalDate.of(2026, 2, 3))
                    .build())
                .document(PdfPart.builder(fixture(BundleFixtures.WORD_DOCUMENT_DOCX))
                    .title(LOCATION_STATEMENT_TITLE)
                    .date(LocalDate.of(2026, 2, 17))
                    .build())
                .build())
            .section(PdfSection.builder(EVIDENCE_SECTION)
                .document(PdfPart.builder(fixture(BundleFixtures.FLYING_PIG_JPG))
                    .title(FLYING_PIG_TITLE)
                    .date(LocalDate.of(2026, 3, 1))
                    .build())
                // A recording cannot go in a PDF: the service writes a page about it instead.
                .document(PdfPart.builder(HtmlPage.builder()
                        .title(HEARING_RECORDING_TITLE)
                        .body("<h1>" + HEARING_RECORDING_TITLE + "</h1>"
                            + "<p>Audio recording, 42 minutes. " + HEARING_RECORDING_NOTE + ".</p>"
                            + "<p><a href=\"" + HEARING_RECORDING_URL + "\">" + HEARING_RECORDING_URL + "</a></p>")
                        .build())
                    .date(LocalDate.of(2026, 3, 14))
                    .build())
                .build())
            .section(PdfSection.builder(CORRESPONDENCE_SECTION)
                .placeholderWhenEmpty()
                .build())
            .build();
    }
}
