package uk.gov.hmcts.ccd.sdk.doctoolkit.support;

import static java.nio.charset.StandardCharsets.UTF_8;

import feign.FeignException;
import feign.Request;
import feign.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;
import uk.gov.hmcts.reform.ccd.document.am.model.CaseDocumentsMetadata;
import uk.gov.hmcts.reform.ccd.document.am.model.Document;
import uk.gov.hmcts.reform.ccd.document.am.model.DocumentTTLRequest;
import uk.gov.hmcts.reform.ccd.document.am.model.DocumentTTLResponse;
import uk.gov.hmcts.reform.ccd.document.am.model.DocumentUploadRequest;
import uk.gov.hmcts.reform.ccd.document.am.model.PatchDocumentMetaDataResponse;
import uk.gov.hmcts.reform.ccd.document.am.model.UploadResponse;

/** CDAM in memory: serves stored documents, fails chosen ones with a status, records writes. */
public final class FakeCdam implements CaseDocumentClientApi {
  public static final String HOST = "http://cdam.test/cases/documents/";

  /** A stored document. */
  public record Stored(byte[] content, String mediaType, String fileName) {
  }

  /** An upload CDAM received. */
  public record Upload(String authorisation, String classification, String caseTypeId,
      String jurisdiction, String fileName, String contentType, byte[] content, UUID id,
      String hashToken) {
  }

  private final Map<UUID, Stored> documents = new ConcurrentHashMap<>();
  private final Map<UUID, Integer> failures = new ConcurrentHashMap<>();
  public final List<String> fetchedAs = new CopyOnWriteArrayList<>();
  public final List<Upload> uploads = new CopyOnWriteArrayList<>();
  public final List<CaseDocumentsMetadata> attachments = new CopyOnWriteArrayList<>();
  private volatile RuntimeException attachFailure;

  /** Stores a document, returning its id. */
  public UUID store(byte[] content, String mediaType, String fileName) {
    UUID id = UUID.randomUUID();
    documents.put(id, new Stored(content, mediaType, fileName));
    return id;
  }

  /** A document id CDAM answers with this HTTP status. */
  public UUID failing(int status) {
    UUID id = UUID.randomUUID();
    failures.put(id, status);
    return id;
  }

  /** Makes attaching documents to cases fail, or with null succeed again. */
  public void failAttaching(RuntimeException failure) {
    this.attachFailure = failure;
  }

  /** The uploads attached to a case. */
  public List<UUID> attached() {
    return attachments.stream()
        .flatMap(metadata -> metadata.getDocumentHashTokens().stream())
        .map(token -> UUID.fromString(token.getId()))
        .toList();
  }

  /** What CDAM holds for an id, including uploads. */
  public Stored get(UUID id) {
    return documents.get(id);
  }

  @Override
  public ResponseEntity<Resource> getDocumentBinary(String authorisation, String serviceAuth,
      UUID documentId) {
    fetchedAs.add(authorisation + " " + serviceAuth);
    Integer status = failures.get(documentId);
    if (status != null) {
      throw error(status);
    }
    Stored stored = documents.get(documentId);
    if (stored == null) {
      throw error(404);
    }
    HttpHeaders headers = new HttpHeaders();
    if (stored.mediaType() != null) {
      headers.set(HttpHeaders.CONTENT_TYPE, stored.mediaType());
    }
    if (stored.fileName() != null) {
      headers.set("OriginalFileName", stored.fileName());
    }
    return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(stored.content()));
  }

  @Override
  public UploadResponse uploadDocuments(String authorisation, String serviceAuth,
      DocumentUploadRequest request) {
    MultipartFile file = request.getFiles().getFirst();
    UUID id = UUID.randomUUID();
    String hashToken = "hash-" + id;
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    uploads.add(new Upload(authorisation, request.getClassification(), request.getCaseTypeId(),
        request.getJurisdictionId(), file.getOriginalFilename(), file.getContentType(), content, id,
        hashToken));
    documents.put(id, new Stored(content, file.getContentType(), file.getOriginalFilename()));
    Document.Link self = new Document.Link();
    self.href = HOST + id;
    Document.Link binary = new Document.Link();
    binary.href = HOST + id + "/binary";
    Document.Links links = new Document.Links();
    links.self = self;
    links.binary = binary;
    return new UploadResponse(List.of(Document.builder().hashToken(hashToken).links(links)
        .originalDocumentName(file.getOriginalFilename()).build()));
  }

  @Override
  public PatchDocumentMetaDataResponse patchDocument(String authorisation, String serviceAuth,
      CaseDocumentsMetadata metadata) {
    if (attachFailure != null) {
      throw attachFailure;
    }
    attachments.add(metadata);
    return null;
  }

  @Override
  public void deleteDocument(String authorisation, String serviceAuth, UUID documentId,
      boolean permanent) {
    throw new UnsupportedOperationException("The doc toolkit never deletes documents");
  }

  @Override
  public Document getMetadataForDocument(String authorisation, String serviceAuth,
      UUID documentId) {
    throw new UnsupportedOperationException();
  }

  @Override
  public DocumentTTLResponse patchDocument(String authorisation, String serviceAuth,
      UUID documentId, DocumentTTLRequest ttl) {
    throw new UnsupportedOperationException();
  }

  private static FeignException error(int status) {
    Request request = Request.create(Request.HttpMethod.GET, HOST, Map.of(), null, UTF_8, null);
    return FeignException.errorStatus("CaseDocumentClientApi#getDocumentBinary",
        Response.builder().status(status).reason("status " + status).request(request)
            .headers(Map.of()).build());
  }
}
