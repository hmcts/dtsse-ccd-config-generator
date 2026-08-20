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
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

/**
 * Failure-path variant of {@link CaseworkerCreateBundle}: the request deliberately references a
 * document that does not exist, so the render fails at the RESOLVE stage with a typed
 * {@link BundleGenerationException} naming the responsible document, and nothing is published.
 * The handler maps the exception's diagnostic message onto the platform's error list — the
 * decentralised route for surfacing an error to the caseworker — which rolls the event back.
 */
@Component
@Slf4j
public class CaseworkerCreateBundleMissingDocument implements CCDConfig<CaseData, State, UserRole> {

    public static final String CASEWORKER_CREATE_BUNDLE_MISSING_DOC = "caseworker-create-bundle-missing-doc";

    public static final String MISSING_DOCUMENT_ID = "vanished-order";

    @Autowired
    private BundleRenderer bundleRenderer;

    @Autowired
    private CaseBundleRepository caseBundleRepository;

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

        BundleRequest request = BundleRequest.builder()
            .externalId(UUID.randomUUID())
            .title("Hearing bundle")
            .fileName("case-" + reference + "-hearing-bundle.pdf")
            .root(BundleSection.builder("Case file")
                .document(BundleDocument.builder()
                    .id(MISSING_DOCUMENT_ID)
                    .title("Order that no longer exists")
                    .date(LocalDate.of(2026, 4, 1))
                    .reference(new DocumentReference(
                        FixtureDocumentResolver.PROVIDER, MISSING_DOCUMENT_ID))
                    .build())
                .build())
            .build();

        BundleExecutionContext context = BundleExecutionContext.builder()
            .caseReference(String.valueOf(reference))
            .initiator(CASEWORKER_CREATE_BUNDLE_MISSING_DOC)
            .build();

        try {
            BundleResult result = bundleRenderer.render(request, context);
            // Unreachable when the fixture is genuinely missing; kept honest so a regression in
            // failure handling shows up as an unexpected saved bundle rather than a silent pass.
            caseBundleRepository.save(reference, request.externalId().toString(), result.output());
            return SubmitResponse.<State>builder()
                .confirmationHeader("Bundle unexpectedly created")
                .build();
        } catch (BundleGenerationException e) {
            log.warn("Bundle generation failed for case {}: {}", reference, e.getMessage());
            // The typed exception's message is caseworker-grade diagnostics: error code, stage,
            // each responsible document, remediation. Returning it as an error rolls the event
            // back and surfaces the message through the platform.
            return SubmitResponse.<State>builder()
                .errors(List.of(e.getMessage()))
                .build();
        }
    }
}
