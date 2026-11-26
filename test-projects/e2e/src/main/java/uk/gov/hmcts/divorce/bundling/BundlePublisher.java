package uk.gov.hmcts.divorce.bundling;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJobs;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.UploadedDoc;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.divorce.bundling.model.CaseBundle;
import uk.gov.hmcts.divorce.bundling.model.CaseBundleDocument;

/**
 * Hearing bundles in this service's own model: recorded as requested in the case event, submitted to the doc
 * toolkit under a key of their own, and completed when the toolkit hands the stored PDF back, or
 * marked failed when it cannot be made.
 */
@Component
public class BundlePublisher {

    /** Each hearing bundle is submitted as this plus its id, so it is never superseded by another. */
    public static final String KEY_PREFIX = "hearing-bundle-";

    @Autowired
    private CaseBundleRepository caseBundleRepository;

    @Autowired
    private DocJobs docJobs;

    /** Records the bundle as requested and submits it; call it in the case event. */
    public CaseBundle request(final long caseReference, final PdfRequest request) {
        CaseBundle bundle = CaseBundle.builder()
            .id(UUID.randomUUID().toString())
            .title(request.title())
            .fileName(request.fileName())
            .stitchStatus("REQUESTED")
            .documents(request.allParts().stream()
                .map(document -> new ListValue<>(UUID.randomUUID().toString(),
                    CaseBundleDocument.builder().name(document.title()).build()))
                .toList())
            .dateAndTime(LocalDateTime.now())
            .build();
        caseBundleRepository.save(caseReference, bundle);
        docJobs.submit(caseReference, KEY_PREFIX + bundle.getId(), request);
        return bundle;
    }

    /** Marks a requested bundle that could not be made. */
    public void failed(final long caseReference, final String bundleId) {
        CaseBundle bundle = caseBundleRepository.find(caseReference, bundleId).orElseThrow();
        bundle.setStitchStatus("FAILED");
        caseBundleRepository.update(caseReference, bundle);
    }

    /** Completes a requested bundle with the PDF the toolkit stored. */
    public void completed(final long caseReference, final String bundleId, final UploadedDoc uploaded) {
        CaseBundle bundle = caseBundleRepository.find(caseReference, bundleId).orElseThrow();
        bundle.setStitchedDocument(uploaded.document());
        bundle.setPageCount(uploaded.pageCount());
        bundle.setStitchStatus("DONE");
        caseBundleRepository.update(caseReference, bundle);
    }
}
