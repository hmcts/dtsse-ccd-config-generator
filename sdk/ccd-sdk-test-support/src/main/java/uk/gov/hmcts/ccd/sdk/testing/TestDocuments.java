package uk.gov.hmcts.ccd.sdk.testing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.ccd.sdk.type.Document;

/**
 * The documents CDAM holds while a test runs. A test stores the documents its case refers to, and
 * the application's own CDAM client then finds, reads and deletes them as it would in CCD.
 */
public final class TestDocuments {

  static final String DM_STORE = "http://dm-store.test/documents/";

  private final Map<UUID, Document> documents = new LinkedHashMap<>();

  /** Stores a document as dm-store would once it was uploaded, and returns it as a case refers to it. */
  public synchronized Document store(String fileName) {
    UUID id = UUID.randomUUID();
    Document document = Document.builder()
        .url(DM_STORE + id)
        .binaryUrl(DM_STORE + id + "/binary")
        .filename(fileName)
        .build();
    documents.put(id, document);
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
    return documents.remove(id) != null;
  }
}
