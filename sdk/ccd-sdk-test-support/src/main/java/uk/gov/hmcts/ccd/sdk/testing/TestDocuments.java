package uk.gov.hmcts.ccd.sdk.testing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import uk.gov.hmcts.ccd.sdk.type.Document;

/**
 * The documents CDAM holds while a test runs. A test stores the documents its case refers to, and
 * the application's own CDAM client can upload, attach, read and delete them.
 */
public final class TestDocuments {

  static final String DM_STORE = "http://dm-store.test/documents/";

  private final Map<UUID, Document> documents = new LinkedHashMap<>();
  private final Map<UUID, byte[]> contents = new LinkedHashMap<>();
  private final Map<UUID, String> mediaTypes = new LinkedHashMap<>();
  private final Map<UUID, Long> cases = new LinkedHashMap<>();

  /** Filename fixture: UTF-8 name bytes labelled application/pdf, not a renderable PDF. */
  public synchronized Document store(String fileName) {
    return store(fileName, "application/pdf", fileName.getBytes(StandardCharsets.UTF_8));
  }

  /** Stores actual bytes for rendering and download assertions. */
  public synchronized Document store(String fileName, String mediaType, byte[] content) {
    UUID id = UUID.randomUUID();
    Document document =
        Document.builder()
            .url(DM_STORE + id)
            .binaryUrl(DM_STORE + id + "/binary")
            .filename(fileName)
            .hashToken("test-hash-" + id)
            .build();
    documents.put(id, document);
    contents.put(id, content.clone());
    mediaTypes.put(id, mediaType);
    return document;
  }

  /** The documents still stored, oldest first; those the application deleted are gone. */
  public synchronized List<Document> stored() {
    return new ArrayList<>(documents.values());
  }

  synchronized Optional<Document> find(UUID id) {
    return Optional.ofNullable(documents.get(id));
  }

  synchronized boolean delete(UUID id) {
    contents.remove(id);
    mediaTypes.remove(id);
    cases.remove(id);
    return documents.remove(id) != null;
  }

  /** Models a source removed from document storage independently of its case reference. */
  public synchronized void delete(Document document) {
    delete(id(document));
  }

  /** Actual content of a stored document; returns a defensive copy. */
  public synchronized byte[] content(Document document) {
    return content(id(document));
  }

  synchronized byte[] content(UUID id) {
    byte[] content = contents.get(id);
    if (content == null) {
      throw new NoSuchElementException("No stored document " + id);
    }
    return content.clone();
  }

  synchronized String mediaType(UUID id) {
    return mediaTypes.get(id);
  }

  /** The attached case reference, or empty before attachment. */
  public synchronized OptionalLong caseReference(Document document) {
    Long reference = cases.get(id(document));
    return reference == null ? OptionalLong.empty() : OptionalLong.of(reference);
  }

  synchronized boolean attach(List<UUID> ids, long caseReference) {
    if (caseReference <= 0 || ids.isEmpty()) {
      throw new IllegalArgumentException("A case reference and documents are required");
    }
    for (UUID id : ids) {
      if (!documents.containsKey(id)) {
        return false;
      }
      Long current = cases.get(id);
      if (current != null && current != caseReference) {
        throw new IllegalStateException("Document is already attached to another case");
      }
    }
    ids.forEach(id -> cases.put(id, caseReference));
    return true;
  }

  private static UUID id(Document document) {
    return UUID.fromString(document.getUrl().substring(document.getUrl().lastIndexOf('/') + 1));
  }
}
