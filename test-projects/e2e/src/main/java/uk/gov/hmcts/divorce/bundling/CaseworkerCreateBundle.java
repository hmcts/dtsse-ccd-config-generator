package uk.gov.hmcts.divorce.bundling;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.LEGAL_ADVISOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.EmptySectionPolicy;
import uk.gov.hmcts.ccd.sdk.bundling.api.MediaPlaceholder;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

/**
 * The document-bundling SDK's worked example: a decentralised event that builds a
 * {@link BundleRequest} from case documents (fixture-backed here), renders it synchronously
 * through the auto-configured {@link BundleRenderer} — PDFs pass through, the image and the
 * office document are converted, the MP3 becomes a generated link page, the empty section
 * renders a visible placeholder — and attaches {@code result.output()} to the case's
 * {@code caseBundles} collection through the service-owned bundle store.
 *
 * <p>Nothing is caught: a {@code BundleGenerationException} propagates, the platform rolls the
 * event back, and no bundle is published.
 */
@Component
@Slf4j
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

    @Autowired
    private BundleRenderer bundleRenderer;

    @Autowired
    private CaseBundleRepository caseBundleRepository;

    @Override
    public void configureDecentralised(final DecentralisedConfigBuilder<CaseData, State, UserRole> configBuilder) {
        configBuilder
            .decentralisedEvent(CASEWORKER_CREATE_BUNDLE, this::submit)
            .forAllStates()
            .name("Create hearing bundle")
            .description("Generate the hearing bundle with the document-bundling SDK")
            .grant(CREATE_READ_UPDATE, CASE_WORKER, SUPER_USER)
            .grantHistoryOnly(LEGAL_ADVISOR, JUDGE);
    }

    private SubmitResponse<State> submit(EventPayload<CaseData, State> payload) {
        final long reference = payload.caseReference();

        BundleRequest request = buildRequest(reference);

        BundleExecutionContext context = BundleExecutionContext.builder()
            .caseReference(String.valueOf(reference))
            .initiator(CASEWORKER_CREATE_BUNDLE)
            .build();

        // Catch nothing: a BundleGenerationException propagates, the platform rolls the event
        // back, nothing was published and the previous bundle (if any) is untouched.
        BundleResult result = bundleRenderer.render(request, context);

        caseBundleRepository.save(reference, request.externalId().toString(), result.output());

        log.info("Bundle {} generated for case {}: {} pages, {} warnings",
            request.externalId(), reference, result.pageCount(), result.warnings().size());
        return SubmitResponse.<State>builder()
            .confirmationHeader("Hearing bundle created")
            .confirmationBody("Generated " + result.pageCount() + " pages")
            .build();
    }

    static BundleRequest buildRequest(final long caseReference) {
        return BundleRequest.builder()
            .externalId(UUID.randomUUID())
            .title(BUNDLE_TITLE)
            .fileName("case-" + caseReference + "-hearing-bundle.pdf")
            .root(BundleSection.builder("Case file")
                .section(BundleSection.builder(APPLICATIONS_SECTION)
                    .document(BundleDocument.builder()
                        .id("app-1")
                        .title(POTENTIAL_ENERGY_TITLE)
                        .date(LocalDate.of(2026, 1, 12))
                        .reference(new DocumentReference(
                            FixtureDocumentResolver.PROVIDER,
                            FixtureDocumentResolver.POTENTIAL_ENERGY_PDF))
                        .build())
                    .document(BundleDocument.builder()
                        .id("app-2")
                        .title(MEDICAL_REPORT_TITLE)
                        .date(LocalDate.of(2026, 2, 3))
                        .reference(new DocumentReference(
                            FixtureDocumentResolver.PROVIDER,
                            FixtureDocumentResolver.CLAIMANT_MEDICAL_REPORT_PDF))
                        .build())
                    .document(BundleDocument.builder()
                        .id("app-3")
                        .title(LOCATION_STATEMENT_TITLE)
                        .date(LocalDate.of(2026, 2, 17))
                        .reference(new DocumentReference(
                            FixtureDocumentResolver.PROVIDER,
                            FixtureDocumentResolver.WORD_DOCUMENT_DOCX))
                        .build())
                    .build())
                .section(BundleSection.builder(EVIDENCE_SECTION)
                    .document(BundleDocument.builder()
                        .id("ev-1")
                        .title(FLYING_PIG_TITLE)
                        .date(LocalDate.of(2026, 3, 1))
                        .reference(new DocumentReference(
                            FixtureDocumentResolver.PROVIDER,
                            FixtureDocumentResolver.FLYING_PIG_JPG))
                        .build())
                    .document(BundleDocument.builder()
                        .id("ev-2")
                        .title(HEARING_RECORDING_TITLE)
                        .date(LocalDate.of(2026, 3, 14))
                        .reference(new DocumentReference(
                            FixtureDocumentResolver.PROVIDER, "hearing-recording-day-2"))
                        .media(MediaPlaceholder.builder()
                            .mediaType("audio/mpeg")
                            .accessUrl(HEARING_RECORDING_URL)
                            .duration(Duration.ofMinutes(42))
                            .note(HEARING_RECORDING_NOTE)
                            .build())
                        .build())
                    .build())
                .section(BundleSection.builder(CORRESPONDENCE_SECTION)
                    .emptySectionPolicy(EmptySectionPolicy.INCLUDE_PLACEHOLDER)
                    .build())
                .build())
            .build();
    }
}
