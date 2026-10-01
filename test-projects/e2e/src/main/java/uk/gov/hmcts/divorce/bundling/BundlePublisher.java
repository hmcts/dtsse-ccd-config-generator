package uk.gov.hmcts.divorce.bundling;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResult;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.divorce.bundling.model.CaseBundle;
import uk.gov.hmcts.divorce.bundling.model.CaseBundleDocument;
import uk.gov.hmcts.divorce.idam.IdamService;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;
import uk.gov.hmcts.reform.ccd.document.am.model.CaseDocumentsMetadata;
import uk.gov.hmcts.reform.ccd.document.am.model.DocumentHashToken;
import uk.gov.hmcts.reform.ccd.document.am.model.DocumentUploadRequest;
import uk.gov.hmcts.reform.ccd.document.am.model.UploadResponse;
import uk.gov.hmcts.reform.ccd.document.am.util.InMemoryMultipartFile;

/**
 * The consumer's side of a bundle: the SDK returns the finished PDF, and this service uploads it
 * to CDAM, attaches it to the case (a decentralised submit handler never routes the document
 * through case-data submission, so nothing else would) and records its own {@link CaseBundle}.
 */
@Component
public class BundlePublisher {

    static final String JURISDICTION = "DIVORCE";
    static final String CASE_TYPE = "E2E";

    @Autowired
    private CaseDocumentClientApi caseDocumentClient;

    @Autowired
    private IdamService idamService;

    @Autowired
    private AuthTokenGenerator authTokenGenerator;

    @Autowired
    private CaseBundleRepository caseBundleRepository;

    public CaseBundle publish(final long caseReference, final BundleRequest request, final BundleResult result) {
        Document stitched = upload(caseReference, result);
        CaseBundle bundle = CaseBundle.builder()
            .id(request.externalId().toString())
            .title(request.title())
            .fileName(result.artifact().fileName())
            .stitchedDocument(stitched)
            .pageCount(result.pageCount())
            .stitchStatus("DONE")
            .documents(request.allDocuments().stream()
                .map(document -> new ListValue<>(document.id(), CaseBundleDocument.builder()
                    .name(document.title())
                    .startPage(placement(result, document.id()).startPage())
                    .pageCount(placement(result, document.id()).pageCount())
                    .build()))
                .toList())
            .dateAndTime(LocalDateTime.now())
            .build();
        caseBundleRepository.save(caseReference, bundle);
        return bundle;
    }

    private Document upload(final long caseReference, final BundleResult result) {
        byte[] content;
        try (InputStream in = result.artifact().open()) {
            content = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the rendered bundle", e);
        }
        String userToken = idamService.retrieveSystemUpdateUserDetails().getAuthToken();
        String serviceToken = authTokenGenerator.generate();
        UploadResponse response = caseDocumentClient.uploadDocuments(userToken, serviceToken,
            new DocumentUploadRequest("PUBLIC", CASE_TYPE, JURISDICTION, List.of(new InMemoryMultipartFile(
                "files", result.artifact().fileName(), result.artifact().mediaType(), content))));
        var uploaded = response.getDocuments().get(0);
        String selfLink = uploaded.links.self.href;
        caseDocumentClient.patchDocument(userToken, serviceToken, new CaseDocumentsMetadata(
            String.valueOf(caseReference), CASE_TYPE, JURISDICTION,
            List.of(new DocumentHashToken(selfLink.substring(selfLink.lastIndexOf('/') + 1), uploaded.hashToken))));
        return Document.builder()
            .url(selfLink)
            .binaryUrl(uploaded.links.binary.href)
            .filename(result.artifact().fileName())
            .build();
    }

    private static DocumentResult placement(final BundleResult result, final String documentId) {
        return result.documents().stream()
            .filter(document -> document.documentId().equals(documentId))
            .findFirst()
            .orElseThrow();
    }
}
