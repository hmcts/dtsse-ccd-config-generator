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
    documentNodes(existingData).forEach(node -> existingDocumentIds.add(documentId(node)));

    List<DocumentHashToken> tokens = new ArrayList<>();
    for (ObjectNode documentNode : documentNodes(submittedData)) {
      String documentId = documentId(documentNode);
      JsonNode documentHashNode = documentNode.get(DOCUMENT_HASH);
      if (existingDocumentIds.contains(documentId)) {
        failIfExistingDocumentHashReturned(documentId, documentHashNode);
      } else {
        tokens.add(buildDocumentHashToken(documentId, documentHashNode));
      }
    }
    return List.copyOf(tokens);
  }

  public JsonNode stripDocumentHashes(JsonNode data) {
    if (data == null || data.isMissingNode()) {
      return data;
    }

    JsonNode copy = data.deepCopy();
    documentNodes(copy).forEach(node -> node.remove(DOCUMENT_HASH));
    return copy;
  }

  private List<ObjectNode> documentNodes(JsonNode node) {
    List<ObjectNode> documentNodes = new ArrayList<>();
    collectDocumentNodes(node, documentNodes);
    return documentNodes;
  }

  private void collectDocumentNodes(JsonNode node, List<ObjectNode> documentNodes) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return;
    }

    if (node.isObject()) {
      ObjectNode objectNode = (ObjectNode) node;
      if (documentId(objectNode) != null) {
        documentNodes.add(objectNode);
      }
      objectNode.fields().forEachRemaining(entry -> collectDocumentNodes(entry.getValue(), documentNodes));
      return;
    }

    if (node.isArray()) {
      node.forEach(child -> collectDocumentNodes(child, documentNodes));
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

}
