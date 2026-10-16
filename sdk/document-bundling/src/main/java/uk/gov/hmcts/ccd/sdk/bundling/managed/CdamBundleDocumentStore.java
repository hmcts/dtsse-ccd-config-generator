package uk.gov.hmcts.ccd.sdk.bundling.managed;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleArtifact;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;
import uk.gov.hmcts.reform.ccd.document.am.model.CaseDocumentsMetadata;
import uk.gov.hmcts.reform.ccd.document.am.model.DocumentHashToken;
import uk.gov.hmcts.reform.ccd.document.am.model.DocumentUploadRequest;

/** Standard CDAM destination. The service needs scoped CREATE, ATTACH and DELETE permissions. */
public class CdamBundleDocumentStore implements BundleDocumentStore {
  private final CaseDocumentClientApi client;
  private final AuthTokenGenerator serviceTokens;
  private final BundleSystemUser systemUser;

  public CdamBundleDocumentStore(
      CaseDocumentClientApi client, AuthTokenGenerator serviceTokens, BundleSystemUser systemUser) {
    this.client = client;
    this.serviceTokens = serviceTokens;
    this.systemUser = systemUser;
  }

  @Override
  public StoredDocument upload(SelectedCaseBundle selection, BundleArtifact artifact) {
    var document =
        client
            .uploadDocuments(
                systemUser.authorisation(),
                serviceTokens.generate(),
                new DocumentUploadRequest(
                    "PUBLIC",
                    selection.caseType(),
                    selection.jurisdiction(),
                    List.of(new ArtifactFile(artifact))))
            .getDocuments()
            .getFirst();
    String url = document.links.self.href;
    return new StoredDocument(
        UUID.fromString(url.substring(url.lastIndexOf('/') + 1)),
        url,
        document.links.binary.href,
        artifact.fileName(),
        document.hashToken);
  }

  @Override
  public void attach(SelectedCaseBundle selection, StoredDocument document) {
    client.patchDocument(
        systemUser.authorisation(),
        serviceTokens.generate(),
        CaseDocumentsMetadata.builder()
            .caseId(String.valueOf(selection.caseReference()))
            .caseTypeId(selection.caseType())
            .jurisdictionId(selection.jurisdiction())
            .documentHashTokens(
                List.of(
                    DocumentHashToken.builder()
                        .id(document.id().toString())
                        .hashToken(document.hash())
                        .build()))
            .build());
  }

  @Override
  public void delete(UUID documentId) {
    try {
      client.deleteDocument(systemUser.authorisation(), serviceTokens.generate(), documentId, true);
    } catch (feign.FeignException.NotFound alreadyDeleted) {
      // Replaying cleanup after a crash must succeed for an already deleted PDF.
    }
  }

  private record ArtifactFile(BundleArtifact artifact) implements MultipartFile {
    @Override
    public String getName() {
      return "files";
    }

    @Override
    public String getOriginalFilename() {
      return artifact.fileName();
    }

    @Override
    public String getContentType() {
      return artifact.mediaType();
    }

    @Override
    public boolean isEmpty() {
      return artifact.size() == 0;
    }

    @Override
    public long getSize() {
      return artifact.size();
    }

    @Override
    public byte[] getBytes() throws IOException {
      try (var input = artifact.open()) {
        return input.readAllBytes();
      }
    }

    @Override
    public java.io.InputStream getInputStream() throws IOException {
      return artifact.open();
    }

    @Override
    public void transferTo(java.io.File destination) throws IOException {
      try (var input = artifact.open()) {
        java.nio.file.Files.copy(
            input, destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }
}
