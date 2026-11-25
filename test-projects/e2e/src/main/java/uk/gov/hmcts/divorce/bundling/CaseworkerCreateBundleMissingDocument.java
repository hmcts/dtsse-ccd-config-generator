package uk.gov.hmcts.divorce.bundling;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.LEGAL_ADVISOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocSource;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocToolkit;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocToolkitException;
import uk.gov.hmcts.ccd.sdk.doctoolkit.MissingDocuments;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfPart;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.UploadedDoc;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

/**
 * Failure-path variant of {@link CaseworkerCreateBundle}: the bundle refers to a CDAM document
 * that does not exist and fails on missing documents, so rendering throws a
 * {@link DocToolkitException} naming it and nothing is published. The handler returns the message
 * as an error, which rolls the event back.
 */
@Component
@Slf4j
public class CaseworkerCreateBundleMissingDocument implements CCDConfig<CaseData, State, UserRole> {

    public static final String CASEWORKER_CREATE_BUNDLE_MISSING_DOC = "caseworker-create-bundle-missing-doc";

    // A CDAM document id that was never stored.
    public static final String MISSING_DOCUMENT_ID = "6a1f2b0e-0000-4000-8000-00000000dead";
    public static final String MISSING_DOCUMENT_TITLE = "Order that no longer exists";

    @Autowired
    private DocToolkit docToolkit;

    @Autowired
    private BundlePublisher bundlePublisher;

    @Override
    public void configureDecentralised(final DecentralisedConfigBuilder<CaseData, State, UserRole> configBuilder) {
        configBuilder
            .decentralisedEvent(CASEWORKER_CREATE_BUNDLE_MISSING_DOC, this::submit)
            .forAllStates()
            .name("Create bundle (missing doc)")
            .description("Bundle referencing a missing document; must fail and publish nothing")
            .grant(CREATE_READ_UPDATE, CASE_WORKER, SUPER_USER)
            .grantHistoryOnly(LEGAL_ADVISOR, JUDGE);
    }

    private SubmitResponse<State> submit(EventPayload<CaseData, State> payload) {
        final long reference = payload.caseReference();
        PdfRequest request = PdfRequest.builder("Hearing bundle")
            .fileName("case-" + reference + "-hearing-bundle.pdf")
            .document(PdfPart.builder(DocSource.cdam(UUID.fromString(MISSING_DOCUMENT_ID)))
                .title(MISSING_DOCUMENT_TITLE)
                .date(LocalDate.of(2026, 4, 1))
                .build())
            // Without this the missing document would get a placeholder page instead.
            .missingDocuments(MissingDocuments.FAIL)
            .build();
        try {
            UploadedDoc uploaded = docToolkit.upload(request, reference);
            // Unreachable while the document is genuinely missing; kept honest so a regression in
            // failure handling shows up as an unexpected saved bundle rather than a silent pass.
            bundlePublisher.publish(reference, request, uploaded);
            return SubmitResponse.<State>builder()
                .confirmationHeader("Bundle unexpectedly created")
                .build();
        } catch (DocToolkitException e) {
            log.warn("Bundle generation failed for case {}: {}", reference, e.getMessage());
            // The message names each document that could not be included and why. Returning it
            // as an error rolls the event back and shows it to the caseworker.
            return SubmitResponse.<State>builder()
                .errors(List.of(e.getMessage()))
                .build();
        }
    }
}
