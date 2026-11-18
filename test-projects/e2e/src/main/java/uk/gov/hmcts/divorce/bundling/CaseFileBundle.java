package uk.gov.hmcts.divorce.bundling;

import java.time.LocalDate;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.managed.BundleSystemUser;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleDefinition;
import uk.gov.hmcts.divorce.idam.IdamService;

/**
 * The document-bundling SDK's managed bundle: the service says which documents are in a case's
 * bundle, and the SDK renders it in the background, stores the PDF in CDAM against the case and
 * replaces it whenever {@link CaseworkerUpdateCaseFile} reports a change.
 */
@Component
public class CaseFileBundle implements CaseBundleDefinition {

    public static final String NAME = "case-file";
    public static final String TITLE = "Case file bundle";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public BundleRequest select(final long caseReference) {
        return BundleRequest.builder()
            .title(TITLE)
            .fileName("case-" + caseReference + "-case-file.pdf")
            .root(BundleSection.builder("Case file")
                .document(BundleDocument.builder()
                    .id("app-1")
                    .title(CaseworkerCreateBundle.POTENTIAL_ENERGY_TITLE)
                    .date(LocalDate.of(2026, 1, 12))
                    .reference(new DocumentReference(
                        FixtureDocumentResolver.PROVIDER, FixtureDocumentResolver.POTENTIAL_ENERGY_PDF))
                    .build())
                .document(BundleDocument.builder()
                    .id("app-2")
                    .title(CaseworkerCreateBundle.MEDICAL_REPORT_TITLE)
                    .date(LocalDate.of(2026, 2, 3))
                    .reference(new DocumentReference(
                        FixtureDocumentResolver.PROVIDER, FixtureDocumentResolver.CLAIMANT_MEDICAL_REPORT_PDF))
                    .build())
                .build())
            .build();
    }

    /** The user the SDK stores bundles in CDAM as. */
    @Bean
    BundleSystemUser bundleSystemUser(final IdamService idamService) {
        return () -> idamService.retrieveSystemUpdateUserDetails().getAuthToken();
    }
}
