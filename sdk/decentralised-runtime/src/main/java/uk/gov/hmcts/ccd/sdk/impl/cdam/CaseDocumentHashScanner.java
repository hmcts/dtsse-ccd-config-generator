package uk.gov.hmcts.ccd.sdk.impl.cdam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.ccd.document.am.model.DocumentHashToken;

@Component
@ConditionalOnProperty(prefix = "ccd.decentralised-runtime.cdam-attach", name = "enabled", havingValue = "true")
public class CaseDocumentHashScanner {

  private static final String DOCUMENT_URL = "document_url";
  private static final String DOCUMENT_HASH = "document_hash";
  private static final String BINARY = "/binary";
  private static final String HEARING_RECORDINGS = "hearing-recordings";
  private static final int UUID_LENGTH = 36;

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
      if (isDocumentNode(objectNode)) {
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
    String hashToken = documentHashNode == null || documentHashNode.isNull()
        ? null
        : documentHashNode.textValue();
    return new DocumentHashToken(documentId, hashToken);
  }

  private String documentId(JsonNode node) {
    return extractDocumentId(node.get(DOCUMENT_URL).asText());
  }

  private String extractDocumentId(String documentUrl) {
    String id = documentUrl.contains(BINARY)
        ? documentUrl.substring(
            documentUrl.length() - UUID_LENGTH - BINARY.length(),
            documentUrl.length() - BINARY.length()
        )
        : documentUrl.substring(documentUrl.length() - UUID_LENGTH);

    try {
      return UUID.fromString(id).toString();
    } catch (IllegalArgumentException ex) {
      throw new CdamAttachException("Document URL does not contain a valid document id", ex);
    }
  }

  private boolean isDocumentNode(ObjectNode node) {
    JsonNode documentUrlNode = node.get(DOCUMENT_URL);
    return isText(documentUrlNode) && !documentUrlNode.asText().contains(HEARING_RECORDINGS);
  }

  private boolean isText(JsonNode node) {
    return node != null && node.isTextual() && !node.asText().isBlank();
  }

}
