package uk.gov.hmcts.divorce.stubs;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.divorce.jsonlegacy.BaseJsonLegacyController;

/**
 * In-memory blob-and-metadata store behind the dm-store stub endpoints: cftlib points the
 * embedded ccd-case-document-am-api at this app as its dm-store
 * (DM_STORE_BASE_URL=localhost:4013), so CDAM uploads land here, CDAM metadata reads and binary
 * downloads are served from here, and CDAM's attach-time metadata PATCH (which is how a document
 * acquires its {@code case_id}) updates the same record — the single source the tests assert
 * attachment against.
 *
 * <p>The JSON-legacy callback fixtures are pre-seeded so the legacy CDAM-attach regression tests
 * exercise the same store as real uploads.
 */
@Component
public class StubDocumentStore {

    public record StoredDocument(
        UUID id,
        String fileName,
        String mimeType,
        String classification,
        Map<String, String> metadata,
        byte[] content,
        Instant createdAt
    ) {
    }

    private final Map<UUID, StoredDocument> documents = new ConcurrentHashMap<>();

    @PostConstruct
    void seedLegacyCallbackFixtures() {
        seedFixture(BaseJsonLegacyController.CALLBACK_DOCUMENT_ID, "callback-acas-document.pdf");
        seedFixture(BaseJsonLegacyController.NULL_HASH_CALLBACK_DOCUMENT_ID, "callback-acas-document-null-hash.pdf");
    }

    private void seedFixture(final String id, final String fileName) {
        var document = new StoredDocument(
            UUID.fromString(id),
            fileName,
            "application/pdf",
            "PUBLIC",
            Map.of("jurisdiction", "EMPLOYMENT", "case_type_id", "case-type-a"),
            "%PDF-stub".getBytes(StandardCharsets.UTF_8),
            Instant.now());
        documents.put(document.id(), document);
    }

    public StoredDocument store(
            final String fileName,
            final String mimeType,
            final String classification,
            final Map<String, String> metadata,
            final byte[] content) {
        var document = new StoredDocument(
            UUID.randomUUID(), fileName, mimeType, classification, Map.copyOf(metadata), content,
            Instant.now());
        documents.put(document.id(), document);
        return document;
    }

    public Optional<StoredDocument> find(final String id) {
        try {
            return Optional.ofNullable(documents.get(UUID.fromString(id)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Merges a metadata update into the stored document, mirroring dm-store's
     * {@code PATCH /documents} semantics: attach-time updates add {@code case_id} (and re-state
     * case type and jurisdiction) without touching the blob.
     *
     * @param id the document id
     * @param update the metadata entries to merge
     * @return whether a document with this id existed
     */
    public boolean updateMetadata(final String id, final Map<String, String> update) {
        UUID documentId;
        try {
            documentId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return documents.computeIfPresent(documentId, (key, existing) -> {
            Map<String, String> merged = new LinkedHashMap<>(existing.metadata());
            merged.putAll(update);
            return new StoredDocument(
                existing.id(), existing.fileName(), existing.mimeType(), existing.classification(),
                Map.copyOf(merged), existing.content(), existing.createdAt());
        }) != null;
    }
}
