package uk.gov.hmcts.divorce.bundling;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.doctoolkit.BundleRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.UploadedDoc;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.divorce.bundling.model.CaseBundle;
import uk.gov.hmcts.divorce.bundling.model.CaseBundleDocument;

/** Records a bundle the toolkit stored in CDAM on the case, in this service's own model. */
@Component
public class BundlePublisher {

    @Autowired
    private CaseBundleRepository caseBundleRepository;

    public CaseBundle publish(final long caseReference, final BundleRequest request, final UploadedDoc uploaded) {
        CaseBundle bundle = CaseBundle.builder()
            .id(UUID.randomUUID().toString())
            .title(request.title())
            .fileName(request.fileName())
            .stitchedDocument(uploaded.document())
            .pageCount(uploaded.pageCount())
            .stitchStatus("DONE")
            .documents(request.allDocuments().stream()
                .map(document -> new ListValue<>(UUID.randomUUID().toString(),
                    CaseBundleDocument.builder().name(document.title()).build()))
                .toList())
            .dateAndTime(LocalDateTime.now())
            .build();
        caseBundleRepository.save(caseReference, bundle);
        return bundle;
    }
}
