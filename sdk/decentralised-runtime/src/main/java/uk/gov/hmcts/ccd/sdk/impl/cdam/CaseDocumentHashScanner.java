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
  private static final String DOCUMENT_HASH = "document_hash";
  private static final String DOCUMENTS_PATH = "/documents/";

  public List<DocumentHashToken> findNewDocumentHashTokens(JsonNode existingData, JsonNode submittedData) {
    Set<String> existingDocumentUrls = new LinkedHashSet<>();
    collectDocumentUrls(existingData, existingDocumentUrls);

    List<DocumentHashToken> tokens = new ArrayList<>();
    collectNewDocumentHashTokens(submittedData, existingDocumentUrls, tokens);
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

  private void collectDocumentUrls(JsonNode node, Set<String> documentUrls) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return;
    }

    if (node.isObject()) {
      JsonNode documentUrlNode = node.get(DOCUMENT_URL);
      if (isText(documentUrlNode)) {
        documentUrls.add(documentUrlNode.asText());
      }
      node.fields().forEachRemaining(entry -> collectDocumentUrls(entry.getValue(), documentUrls));
      return;
    }

    if (node.isArray()) {
      node.forEach(child -> collectDocumentUrls(child, documentUrls));
    }
  }

  private void collectNewDocumentHashTokens(JsonNode node,
                                            Set<String> existingDocumentUrls,
                                            List<DocumentHashToken> tokens) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return;
    }

    if (node.isObject()) {
      JsonNode documentUrlNode = node.get(DOCUMENT_URL);
      if (isText(documentUrlNode) && !existingDocumentUrls.contains(documentUrlNode.asText())) {
        tokens.add(buildDocumentHashToken(documentUrlNode.asText(), node.get(DOCUMENT_HASH)));
      }
      node.fields().forEachRemaining(entry -> collectNewDocumentHashTokens(entry.getValue(), existingDocumentUrls,
          tokens));
      return;
    }

    if (node.isArray()) {
      node.forEach(child -> collectNewDocumentHashTokens(child, existingDocumentUrls, tokens));
    }
  }

  private DocumentHashToken buildDocumentHashToken(String documentUrl, JsonNode documentHashNode) {
    if (!isText(documentHashNode)) {
      throw new CdamAttachException("New document " + documentUrl + " is missing document_hash");
    }

    return new DocumentHashToken(extractDocumentId(documentUrl), documentHashNode.asText());
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
