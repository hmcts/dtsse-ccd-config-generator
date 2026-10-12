package uk.gov.hmcts.ccd.sdk.testing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Client;
import feign.Request;
import feign.Response;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import uk.gov.hmcts.ccd.sdk.type.Document;

/**
 * Answers the application's CDAM calls for a document from the documents a test stored: its
 * metadata, its content, a new time to live and its deletion. Unknown documents are not found, and
 * every other request, such as an upload, goes to the application's own client.
 */
final class TestCaseDocumentClient implements Client {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern DOCUMENT = Pattern.compile(".*/cases/documents/([0-9a-fA-F-]{36})(/binary)?");

  private final Client delegate;
  private final TestDocuments documents;

  TestCaseDocumentClient(Client delegate, TestDocuments documents) {
    this.delegate = delegate;
    this.documents = documents;
  }

  @Override
  public Response execute(Request request, Request.Options options) throws IOException {
    Matcher matcher = DOCUMENT.matcher(URI.create(request.url()).getPath());
    if (!matcher.matches()) {
      return delegate.execute(request, options);
    }
    UUID id = UUID.fromString(matcher.group(1));
    boolean binary = matcher.group(2) != null;
    Optional<Document> document = documents.find(id);
    if (document.isEmpty()) {
      return respond(request, 404, "application/json", "{\"error\":\"not_found\"}");
    }
    return switch (request.httpMethod()) {
      case GET -> binary
          ? respond(request, 200, "application/pdf", document.get().getFilename())
          : respond(request, 200, "application/json", metadata(document.get()));
      case PATCH -> respond(request, 200, "application/json", "{}");
      case DELETE -> {
        documents.delete(id);
        yield respond(request, 204, "application/json", "");
      }
      default -> delegate.execute(request, options);
    };
  }

  /** The document as CDAM describes it, linking to it in dm-store. */
  private static String metadata(Document document) throws JsonProcessingException {
    String now = Instant.now().toString();
    return JSON.writeValueAsString(Map.of(
        "classification", "PUBLIC",
        "size", document.getFilename().length(),
        "mimeType", "application/pdf",
        "originalDocumentName", document.getFilename(),
        "createdOn", now,
        "modifiedOn", now,
        "_links", Map.of(
            "self", Map.of("href", document.getUrl()),
            "binary", Map.of("href", document.getBinaryUrl()))));
  }

  private static Response respond(Request request, int status, String contentType, String body) {
    return Response.builder()
        .request(request)
        .status(status)
        .reason(status == 404 ? "Not Found" : "OK")
        .headers(Map.of("Content-Type", List.of(contentType)))
        .body(body, StandardCharsets.UTF_8)
        .build();
  }
}
