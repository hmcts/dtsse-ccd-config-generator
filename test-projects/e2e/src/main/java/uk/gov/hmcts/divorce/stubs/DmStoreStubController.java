package uk.gov.hmcts.divorce.stubs;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * dm-store stub endpoints for the embedded ccd-case-document-am-api, which cftlib points at this
 * app (DM_STORE_BASE_URL=localhost:4013), with {@link StubDocumentStore} as the single source of
 * document state. Implements the calls CDAM makes: {@code POST /documents} (multipart
 * classification/metadata/ttl/files, answered with dm-store's HAL shape),
 * {@code GET /documents/{id}} (metadata, served from the same record every other endpoint uses),
 * {@code GET /documents/{id}/binary}, and {@code PATCH /documents} — CDAM's attach flow, which
 * merges {@code case_id}/{@code case_type_id}/{@code jurisdiction} into the stored metadata so a
 * subsequent metadata read genuinely reflects the attachment.
 *
 * <p>The TTL is realistic and enforced: upload time plus {@link #TTL_WINDOW}, the way dm-store
 * grants a bounded lease to an unattached document. A document whose TTL has expired without a
 * {@code case_id} ever being attached is served as 404, mirroring dm-store's expired-document
 * disposal — so a far-future TTL can never stand in for a real case attachment (the tests'
 * actual attachment proof remains the {@code case_id} metadata assertions through the real
 * CDAM).
 */
@RestController
@Slf4j
public class DmStoreStubController {

    static final String DM_STORE_BASE_URL = "http://localhost:4013";

    /** How long an uploaded document remains retrievable while unattached, as dm-store's TTL. */
    static final Duration TTL_WINDOW = Duration.ofMinutes(30);

    private static final DateTimeFormatter DM_STORE_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").withZone(ZoneOffset.UTC);

    @Autowired
    private StubDocumentStore documentStore;

    @PostMapping(value = "/documents", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> uploadDocuments(
            @RequestParam("classification") String classification,
            @RequestParam(name = "metadata[jurisdiction]", required = false) String jurisdiction,
            @RequestParam(name = "metadata[case_type_id]", required = false) String caseTypeId,
            @RequestParam("files") List<MultipartFile> files) throws IOException {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (jurisdiction != null) {
            metadata.put("jurisdiction", jurisdiction);
        }
        if (caseTypeId != null) {
            metadata.put("case_type_id", caseTypeId);
        }

        List<Map<String, Object>> documents = files.stream().map(file -> {
            byte[] content;
            try {
                content = file.getBytes();
            } catch (IOException e) {
                throw new IllegalStateException("Could not read uploaded file", e);
            }
            var stored = documentStore.store(
                file.getOriginalFilename(), file.getContentType(), classification, metadata, content);
            log.info("dm-store stub stored document {} ({} bytes, {})",
                stored.id(), content.length, file.getOriginalFilename());
            return documentHal(stored);
        }).toList();

        return Map.of("_embedded", Map.of("documents", documents));
    }

    @GetMapping(value = "/documents/{documentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> documentMetadata(@PathVariable String documentId) {
        return findLive(documentId)
            .map(stored -> ResponseEntity.ok(documentHal(stored)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/documents/{documentId}/binary")
    public ResponseEntity<byte[]> documentBinary(@PathVariable String documentId) {
        return findLive(documentId)
            .map(stored -> ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, stored.mimeType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + stored.fileName())
                .body(stored.content()))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * dm-store disposes of a document whose TTL expired without a case attachment; mirror that
     * by serving such documents as gone. An attached document ({@code case_id} present) never
     * expires.
     */
    private Optional<StubDocumentStore.StoredDocument> findLive(String documentId) {
        return documentStore.find(documentId).filter(stored ->
            stored.metadata().containsKey("case_id")
                || Instant.now().isBefore(stored.createdAt().plus(TTL_WINDOW)));
    }

    /**
     * dm-store's bulk metadata update, which CDAM's {@code attachToCase} drives: each entry
     * merges its metadata (notably {@code case_id}) into the stored document.
     */
    @PatchMapping(value = "/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> patchDocumentsMetadata(@RequestBody Map<String, Object> body) {
        Object documents = body.get("documents");
        if (documents instanceof List<?> documentUpdates) {
            documentUpdates.forEach(this::applyMetadataUpdate);
        }

        return new ResponseEntity<>(HttpStatus.OK);
    }

    private void applyMetadataUpdate(Object documentUpdate) {
        if (!(documentUpdate instanceof Map<?, ?> update)) {
            return;
        }
        Object documentId = update.get("documentId");
        if (documentId == null) {
            documentId = update.get("document_id");
        }
        Object metadata = update.get("metadata");
        if (documentId == null || !(metadata instanceof Map<?, ?> metadataMap)) {
            return;
        }
        Map<String, String> updateEntries = new LinkedHashMap<>();
        metadataMap.forEach((key, value) -> updateEntries.put(String.valueOf(key), String.valueOf(value)));
        boolean updated = documentStore.updateMetadata(documentId.toString(), updateEntries);
        log.info("dm-store stub metadata PATCH for document {} ({}): {}",
            documentId, updated ? "applied" : "unknown document", updateEntries);
    }

    private static Map<String, Object> documentHal(StubDocumentStore.StoredDocument stored) {
        String selfHref = DM_STORE_BASE_URL + "/documents/" + stored.id();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("classification", stored.classification());
        document.put("size", stored.content().length);
        document.put("mimeType", stored.mimeType());
        document.put("originalDocumentName", stored.fileName());
        document.put("createdOn", DM_STORE_TIMESTAMP.format(stored.createdAt()));
        document.put("ttl", DM_STORE_TIMESTAMP.format(stored.createdAt().plus(TTL_WINDOW)));
        document.put("metadata", stored.metadata());
        document.put("_links", Map.of(
            "self", Map.of("href", selfHref),
            "binary", Map.of("href", selfHref + "/binary")
        ));
        return document;
    }
}
