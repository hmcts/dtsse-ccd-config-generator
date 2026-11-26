package uk.gov.hmcts.ccd.sdk.doctoolkit.cdam;

import feign.FeignException;
import feign.RetryableException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocFailure;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocToolkitSystemUser;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;
import uk.gov.hmcts.reform.ccd.document.am.model.CaseDocumentsMetadata;
import uk.gov.hmcts.reform.ccd.document.am.model.DocumentHashToken;
import uk.gov.hmcts.reform.ccd.document.am.model.DocumentUploadRequest;

/** Fetches documents from CDAM and stores results in it, as the service's system user. */
public final class CdamDocuments {
  private static final String ORIGINAL_FILE_NAME_HEADER = "OriginalFileName";

  private final CaseDocumentClientApi client;
  private final AuthTokenGenerator serviceTokens;
  private final DocToolkitSystemUser systemUser;
  private final CaseLocator cases;

  public CdamDocuments(CaseDocumentClientApi client, AuthTokenGenerator serviceTokens,
      DocToolkitSystemUser systemUser, CaseLocator cases) {
    this.client = client;
    this.serviceTokens = serviceTokens;
    this.systemUser = systemUser;
    this.cases = cases;
  }

  /** A fetched document: where it was written, and what CDAM says it is. */
  public record Fetched(Path file, String fileName, String mediaType) {
  }

  /**
   * Fetches a document's content into {@code into}.
   *
   * @param maxBytes the most that is read; a larger document fails as {@code TOO_LARGE}
   */
  public Fetched fetch(UUID documentId, Path into, long maxBytes) throws CdamFetchException {
    ResponseEntity<Resource> response;
    try {
      response = client.getDocumentBinary(
          systemUser.authorisation(), serviceTokens.generate(), documentId);
    } catch (FeignException e) {
      throw failure(documentId, e);
    }
    Resource body = response.getBody();
    if (body == null) {
      throw new CdamFetchException(DocFailure.Reason.UNREADABLE,
          "CDAM returned no content for " + documentId, null);
    }
    try (InputStream in = body.getInputStream(); OutputStream out = Files.newOutputStream(into)) {
      byte[] buffer = new byte[64 * 1024];
      long total = 0;
      int read;
      while ((read = in.read(buffer)) >= 0) {
        total += read;
        if (total > maxBytes) {
          throw new CdamFetchException(DocFailure.Reason.TOO_LARGE,
              documentId + " is larger than " + maxBytes + " bytes", null);
        }
        out.write(buffer, 0, read);
      }
    } catch (IOException e) {
      throw new CdamFetchException(DocFailure.Reason.UNAVAILABLE,
          "Reading " + documentId + " from CDAM failed: " + e.getClass().getSimpleName(), e);
    }
    HttpHeaders headers = response.getHeaders();
    String fileName = Optional.ofNullable(headers.getFirst(ORIGINAL_FILE_NAME_HEADER))
        .filter(name -> !name.isBlank())
        .orElse(documentId.toString());
    return new Fetched(into, fileName, mediaType(headers));
  }

  /**
   * A document stored in CDAM and not yet attached to a case. CDAM gives it a time to live, so
   * dm-store deletes it unless it is {@link #attach attached}.
   *
   * @param document the value for a CCD document field
   * @param hashToken what CDAM needs to attach it
   */
  public record Stored(Document document, String hashToken) {
  }

  /**
   * Stores {@code file} in CDAM for the case, unattached.
   */
  public Stored store(Path file, String fileName, long caseId, String classification) {
    CaseLocator.CaseType caseType = cases.locate(caseId);
    var stored = client.uploadDocuments(systemUser.authorisation(), serviceTokens.generate(),
            new DocumentUploadRequest(classification, caseType.caseTypeId(), caseType.jurisdiction(),
                List.of(new PdfFile(file, fileName))))
        .getDocuments()
        .getFirst();
    return new Stored(Document.builder()
        .url(stored.links.self.href)
        .binaryUrl(stored.links.binary.href)
        .filename(fileName)
        .uploadTimestamp(LocalDateTime.now())
        .build(), stored.hashToken);
  }

  /**
   * Attaches a stored document to its case, which clears its time to live. Attaching it again
   * to the same case does nothing.
   */
  public void attach(Stored stored, long caseId) {
    CaseLocator.CaseType caseType = cases.locate(caseId);
    String url = stored.document().getUrl();
    client.patchDocument(systemUser.authorisation(), serviceTokens.generate(),
        CaseDocumentsMetadata.builder()
            .caseId(String.valueOf(caseId))
            .caseTypeId(caseType.caseTypeId())
            .jurisdictionId(caseType.jurisdiction())
            .documentHashTokens(List.of(DocumentHashToken.builder()
                .id(url.substring(url.lastIndexOf('/') + 1))
                .hashToken(stored.hashToken())
                .build()))
            .build());
  }

  private static CdamFetchException failure(UUID documentId, FeignException e) {
    if (e instanceof RetryableException || e.status() >= 500 || e.status() == 429
        || e.status() < 0) {
      return new CdamFetchException(DocFailure.Reason.UNAVAILABLE,
          "CDAM was unavailable for " + documentId + " (status " + e.status() + ")", e);
    }
    if (e.status() == 404) {
      return new CdamFetchException(DocFailure.Reason.NOT_FOUND,
          documentId + " is not in CDAM", e);
    }
    if (e.status() == 401 || e.status() == 403) {
      return new CdamFetchException(DocFailure.Reason.ACCESS_DENIED,
          "CDAM refused access to " + documentId + " (status " + e.status() + ")", e);
    }
    return new CdamFetchException(DocFailure.Reason.UNREADABLE,
        "CDAM rejected the request for " + documentId + " (status " + e.status() + ")", e);
  }

  private static String mediaType(HttpHeaders headers) {
    try {
      MediaType type = headers.getContentType();
      return type == null ? null : type.getType() + "/" + type.getSubtype();
    } catch (InvalidMediaTypeException e) {
      return null;
    }
  }

  /** The rendered PDF as the multipart part CDAM's client sends. */
  private record PdfFile(Path file, String fileName) implements MultipartFile {
    @Override
    public String getName() {
      return "files";
    }

    @Override
    public String getOriginalFilename() {
      return fileName;
    }

    @Override
    public String getContentType() {
      return "application/pdf";
    }

    @Override
    public boolean isEmpty() {
      return getSize() == 0;
    }

    @Override
    public long getSize() {
      try {
        return Files.size(file);
      } catch (IOException e) {
        return 0;
      }
    }

    @Override
    public byte[] getBytes() throws IOException {
      return Files.readAllBytes(file);
    }

    @Override
    public InputStream getInputStream() throws IOException {
      return Files.newInputStream(file);
    }

    @Override
    public void transferTo(File destination) throws IOException {
      Files.copy(file, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
