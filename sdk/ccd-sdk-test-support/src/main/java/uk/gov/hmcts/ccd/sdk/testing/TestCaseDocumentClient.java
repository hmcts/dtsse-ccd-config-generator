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
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import uk.gov.hmcts.ccd.sdk.type.Document;

/**
 * Answers CDAM storage calls from an application's own Feign clients, routing by endpoint path so
 * custom CDAM interfaces work too. Requests outside the supported endpoints use the delegate.
 *
 * <p>This fixture does not simulate CCD permissions or validate attachment hash tokens. TTL updates
 * are echoed without storing or expiring them, and every delete removes fixture content regardless
 * of the permanent flag.
 */
final class TestCaseDocumentClient implements Client {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern DOCUMENT =
      Pattern.compile(".*/cases/documents/([0-9a-fA-F-]{36})(/binary)?");

  private final Client delegate;
  private final TestDocuments documents;

  TestCaseDocumentClient(Client delegate, TestDocuments documents) {
    this.delegate = delegate;
    this.documents = documents;
  }

  @Override
  public Response execute(Request request, Request.Options options) throws IOException {
    String path = URI.create(request.url()).getPath();
    if (path.endsWith("/cases/documents")) {
      if (request.httpMethod() == Request.HttpMethod.POST) {
        return upload(request, options);
      }
    }
    if (path.endsWith("/cases/documents/attachToCase")
        && request.httpMethod() == Request.HttpMethod.PATCH) {
      return attach(request);
    }
    Matcher matcher = DOCUMENT.matcher(path);
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
      case GET ->
          binary
              ? Response.builder()
                  .request(request)
                  .status(200)
                  .reason("OK")
                  .headers(
                      Map.of(
                          "Content-Type",
                          List.of(documents.mediaType(id)),
                          "OriginalFileName",
                          List.of(document.get().getFilename())))
                  .body(documents.content(id))
                  .build()
              : respond(request, 200, "application/json", metadata(document.get(), id));
      case PATCH -> respond(request, 200, "application/json", newTtl(request));
      case DELETE -> {
        documents.delete(id);
        yield respond(request, 204, "application/json", "");
      }
      default -> delegate.execute(request, options);
    };
  }

  private Response upload(Request request, Request.Options options) throws IOException {
    String contentType =
        request.headers().entrySet().stream()
            .filter(header -> header.getKey().equalsIgnoreCase("Content-Type"))
            .flatMap(header -> header.getValue().stream())
            .findFirst()
            .orElse("");
    Matcher boundary = Pattern.compile("boundary=\"?([^\";]+)").matcher(contentType);
    if (!boundary.find() || request.body() == null) {
      return delegate.execute(request, options);
    }
    String wire = new String(request.body(), StandardCharsets.ISO_8859_1);
    List<UploadPart> files = new java.util.ArrayList<>();
    for (String part : wire.split(Pattern.quote("--" + boundary.group(1)))) {
      int separator = part.indexOf("\r\n\r\n");
      if (separator < 0) {
        continue;
      }
      String headers =
          new String(
              part.substring(0, separator).getBytes(StandardCharsets.ISO_8859_1),
              StandardCharsets.UTF_8);
      Matcher filename = Pattern.compile("filename=\"([^\"]+)\"").matcher(headers);
      if (!filename.find()) {
        if (Pattern.compile("(?i)(?:^|;)\\s*filename\\*?=").matcher(headers).find()) {
          return respond(request, 400, "application/json", "{}");
        }
        continue;
      }
      Matcher type = Pattern.compile("(?im)^Content-Type:[ \t]*([^\r\n]+)").matcher(headers);
      String body = part.substring(separator + 4);
      if (body.endsWith("\r\n")) {
        body = body.substring(0, body.length() - 2);
      }
      files.add(
          new UploadPart(
              filename.group(1),
              type.find() ? type.group(1) : "application/octet-stream",
              body.getBytes(StandardCharsets.ISO_8859_1)));
    }
    if (files.isEmpty()) {
      return respond(request, 400, "application/json", "{}");
    }
    List<Map<String, Object>> uploaded = new java.util.ArrayList<>();
    for (UploadPart file : files) {
      Document document = documents.store(file.name(), file.mediaType(), file.content());
      UUID id =
          UUID.fromString(document.getUrl().substring(document.getUrl().lastIndexOf('/') + 1));
      Map<String, Object> entry =
          JSON.readValue(
              metadata(document, id),
              new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
      entry.put("hashToken", document.getHashToken());
      uploaded.add(entry);
    }
    return respond(
        request, 200, "application/json", JSON.writeValueAsString(Map.of("documents", uploaded)));
  }

  private Response attach(Request request) throws IOException {
    List<UUID> ids = new java.util.ArrayList<>();
    long reference;
    try {
      var metadata = JSON.readTree(request.body());
      reference = Long.parseLong(metadata.path("caseId").asText());
      if (reference <= 0
          || !metadata.path("documentHashTokens").isArray()
          || metadata.path("documentHashTokens").isEmpty()) {
        return respond(request, 400, "application/json", "{}");
      }
      for (var token : metadata.path("documentHashTokens")) {
        ids.add(UUID.fromString(token.path("id").asText()));
      }
    } catch (IllegalArgumentException | IOException malformed) {
      return respond(request, 400, "application/json", "{}");
    }
    try {
      return documents.attach(ids, reference)
          ? respond(request, 200, "application/json", "{}")
          : respond(request, 404, "application/json", "{}");
    } catch (IllegalStateException alreadyAttached) {
      return respond(request, 409, "application/json", "{}");
    }
  }

  private record UploadPart(String name, String mediaType, byte[] content) {}

  /** The document as CDAM describes it, linking to it in dm-store. */
  private String metadata(Document document, UUID id) throws JsonProcessingException {
    String now = Instant.now().toString();
    return JSON.writeValueAsString(
        Map.of(
            "classification",
            "PUBLIC",
            "size",
            documents.content(id).length,
            "mimeType",
            documents.mediaType(id),
            "originalDocumentName",
            document.getFilename(),
            "createdOn",
            now,
            "modifiedOn",
            now,
            "_links",
            Map.of(
                "self", Map.of("href", document.getUrl()),
                "binary", Map.of("href", document.getBinaryUrl()))));
  }

  /** The document's new time to live, as CDAM confirms the one the application asked for. */
  private static String newTtl(Request request) throws IOException {
    String now = LocalDateTime.now().toString();
    Map<String, Object> ttl = new LinkedHashMap<>();
    ttl.put("createdOn", now);
    ttl.put("modifiedOn", now);
    ttl.put(
        "ttl",
        request.body() == null ? null : JSON.readTree(request.body()).path("ttl").asText(null));
    return JSON.writeValueAsString(ttl);
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
