package uk.gov.hmcts.ccd.sdk.impl.cdam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class CaseDocumentHashScanner {

  private static final String DOCUMENT_URL = "document_url";
  private static final String DOCUMENT_BINARY_URL = "document_binary_url";
  private static final String DOCUMENT_HASH = "document_hash";
  private static final String DOCUMENTS_PATH = "/documents/";

  public List<DocumentHashToken> findNewDocumentHashTokens(JsonNode existingData, JsonNode submittedData) {
    Set<String> existingDocumentIds = new LinkedHashSet<>();
    collectDocumentIds(existingData, existingDocumentIds);

    List<DocumentHashToken> tokens = new ArrayList<>();
    collectNewDocumentHashTokens(submittedData, existingDocumentIds, tokens);
    return List.copyOf(tokens);
  }

  public JsonNode stripDocumentHashes(JsonNode data) {
    if (data == null || data.isMissingNode()) {
      return data;
    }

    JsonNode copy = data.deepCopy();
    stripDocumentHashesInPlace(copy);
    return copy;
  }

  private void collectDocumentIds(JsonNode node, Set<String> documentIds) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return;
    }

    if (node.isObject()) {
      String documentId = documentId(node);
      if (documentId != null) {
        documentIds.add(documentId);
      }
      node.fields().forEachRemaining(entry -> collectDocumentIds(entry.getValue(), documentIds));
      return;
    }

    if (node.isArray()) {
      node.forEach(child -> collectDocumentIds(child, documentIds));
    }
  }

  private void collectNewDocumentHashTokens(JsonNode node,
                                            Set<String> existingDocumentIds,
                                            List<DocumentHashToken> tokens) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return;
    }

    if (node.isObject()) {
      String documentId = documentId(node);
      if (documentId != null) {
        JsonNode documentHashNode = node.get(DOCUMENT_HASH);
        if (existingDocumentIds.contains(documentId)) {
          failIfExistingDocumentHashReturned(documentId, documentHashNode);
        } else {
          tokens.add(buildDocumentHashToken(documentId, documentHashNode));
        }
      }
      node.fields().forEachRemaining(entry -> collectNewDocumentHashTokens(entry.getValue(), existingDocumentIds,
          tokens));
      return;
    }

    if (node.isArray()) {
      node.forEach(child -> collectNewDocumentHashTokens(child, existingDocumentIds, tokens));
    }
  }

  private void failIfExistingDocumentHashReturned(String documentId, JsonNode documentHashNode) {
    if (isText(documentHashNode)) {
      throw new CdamAttachException("Existing document " + documentId + " must not return document_hash");
    }
  }

  private DocumentHashToken buildDocumentHashToken(String documentId, JsonNode documentHashNode) {
    if (!isText(documentHashNode)) {
      throw new CdamAttachException("New document " + documentId + " is missing document_hash");
    }

    return new DocumentHashToken(documentId, documentHashNode.asText());
  }

  private String documentId(JsonNode node) {
    JsonNode documentUrlNode = node.get(DOCUMENT_URL);
    if (isText(documentUrlNode)) {
      return extractDocumentId(documentUrlNode.asText());
    }

    JsonNode documentBinaryUrlNode = node.get(DOCUMENT_BINARY_URL);
    if (isText(documentBinaryUrlNode)) {
      return extractDocumentId(documentBinaryUrlNode.asText());
    }

    return null;
  }

  private String extractDocumentId(String documentUrl) {
    String path = path(documentUrl);
    int index = path.lastIndexOf(DOCUMENTS_PATH);
    if (index < 0) {
      throw new CdamAttachException("Document URL does not contain " + DOCUMENTS_PATH + ": " + documentUrl);
    }

    String id = path.substring(index + DOCUMENTS_PATH.length());
    int slash = id.indexOf('/');
    if (slash >= 0) {
      id = id.substring(0, slash);
    }

    try {
      return UUID.fromString(id).toString();
    } catch (IllegalArgumentException ex) {
      throw new CdamAttachException("Document URL does not contain a valid document id: " + documentUrl, ex);
    }
  }

  private String path(String documentUrl) {
    try {
      URI uri = URI.create(documentUrl);
      return uri.getPath() == null ? documentUrl : uri.getPath();
    } catch (IllegalArgumentException ex) {
      return documentUrl;
    }
  }

  private boolean isText(JsonNode node) {
    return node != null && node.isTextual() && !node.asText().isBlank();
  }

  private void stripDocumentHashesInPlace(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return;
    }

    if (node.isObject()) {
      ObjectNode objectNode = (ObjectNode) node;
      objectNode.remove(DOCUMENT_HASH);
      objectNode.fields().forEachRemaining(entry -> stripDocumentHashesInPlace(entry.getValue()));
      return;
    }

    if (node.isArray()) {
      node.forEach(this::stripDocumentHashesInPlace);
    }
  }
}
