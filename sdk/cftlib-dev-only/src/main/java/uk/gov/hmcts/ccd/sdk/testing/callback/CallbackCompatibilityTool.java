package uk.gov.hmcts.ccd.sdk.testing.callback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Generates an inventory and optional HTTP compatibility report for CCD callback routes.
 */
public final class CallbackCompatibilityTool {

  private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  private static final Set<String> LOCAL_CALLBACK_TOKENS = Set.of("ET_COS_URL", "CCD_DEF_ET_COS_URL");
  private static final String ABOUT_TO_SUBMIT_FIELD = "CallBackURLAboutToSubmitEvent";
  private static final String SUBMITTED_FIELD = "CallBackURLSubmittedEvent";

  private CallbackCompatibilityTool() {
  }

  public static void main(String[] rawArgs) throws Exception {
    Options options = Options.parse(rawArgs);
    List<CallbackEndpoint> endpoints = DefinitionScanner.scan(options.definitions(), options.callbackBaseToken());
    List<CallbackResult> results = new CallbackRunner(options).run(endpoints);
    ReportWriter.write(options.out(), results);

    long failed = results.stream().filter(CallbackResult::isFailure).count();
    if (options.failOnDiscrepancy() && failed > 0) {
      throw new IllegalStateException("Callback compatibility report found " + failed + " failures");
    }
  }

  private enum CallbackKind {
    ABOUT_TO_SUBMIT("about-to-submit", ABOUT_TO_SUBMIT_FIELD),
    SUBMITTED("submitted", SUBMITTED_FIELD);

    private final String label;
    private final String field;

    CallbackKind(String label, String field) {
      this.label = label;
      this.field = field;
    }
  }

  private enum Status {
    INVENTORY,
    PASS,
    DIRECT_ONLY,
    EXTERNAL,
    HTTP_ERROR,
    BAD_RESPONSE_SHAPE,
    DISCREPANCY
  }

  private record CallbackEndpoint(
      String caseTypeId,
      String eventId,
      String eventName,
      CallbackKind kind,
      String callbackUrl,
      String localPath,
      String initialState,
      boolean external,
      Path source
  ) {
  }

  private record HttpOutcome(int status, JsonNode body, String error) {
    boolean success() {
      return status >= 200 && status < 300 && error == null;
    }
  }

  private record CallbackResult(
      CallbackEndpoint endpoint,
      Status status,
      HttpOutcome direct,
      HttpOutcome runtime,
      List<String> discrepancies
  ) {
    boolean isFailure() {
      return status == Status.HTTP_ERROR
          || status == Status.BAD_RESPONSE_SHAPE
          || status == Status.DISCREPANCY;
    }
  }

  private record Options(
      Path definitions,
      URI baseUrl,
      Path out,
      Mode mode,
      String callbackBaseToken,
      String authorization,
      String serviceAuthorization,
      long caseRefStart,
      boolean failOnDiscrepancy
  ) {
    static Options parse(String[] args) {
      Map<String, String> parsed = new LinkedHashMap<>();
      Set<String> flags = new LinkedHashSet<>();

      for (int i = 0; i < args.length; i++) {
        String arg = args[i];
        if (!arg.startsWith("--")) {
          throw new IllegalArgumentException("Unexpected argument: " + arg);
        }
        String key = arg.substring(2);
        if ("fail-on-discrepancy".equals(key)) {
          flags.add(key);
          continue;
        }
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException("Missing value for " + arg);
        }
        parsed.put(key, args[++i]);
      }

      Path definitions = Path.of(parsed.getOrDefault("definitions", "ccd-definitions"));
      URI baseUrl = URI.create(parsed.getOrDefault("base-url", "http://localhost:8081"));
      Path out = Path.of(parsed.getOrDefault("out", "test-report"));
      Mode mode = Mode.valueOf(parsed.getOrDefault("mode", "inventory").toUpperCase(Locale.ROOT).replace('-', '_'));
      String callbackBaseToken = parsed.getOrDefault("callback-base-token", "ET_COS_URL");
      String authorization = parsed.getOrDefault("authorization", "Bearer callback-compatibility");
      String serviceAuthorization = parsed.getOrDefault("service-authorization", "Bearer callback-compatibility-s2s");
      long caseRefStart = Long.parseLong(parsed.getOrDefault("case-ref-start", "9900000000000000"));

      return new Options(
          definitions,
          baseUrl,
          out,
          mode,
          callbackBaseToken,
          authorization,
          serviceAuthorization,
          caseRefStart,
          flags.contains("fail-on-discrepancy")
      );
    }
  }

  private enum Mode {
    INVENTORY,
    DIRECT_HTTP,
    SIDE_BY_SIDE
  }

  private static final class DefinitionScanner {

    private DefinitionScanner() {
    }

    static List<CallbackEndpoint> scan(Path definitionsRoot, String callbackBaseToken) throws IOException {
      List<CallbackEndpoint> endpoints = new ArrayList<>();
      Map<String, String> defaultStates = defaultStates(definitionsRoot);
      try (Stream<Path> paths = Files.walk(definitionsRoot)) {
        for (Path path : paths
            .filter(Files::isRegularFile)
            .filter(DefinitionScanner::isDefinitionJson)
            .sorted(Comparator.naturalOrder())
            .toList()) {
          JsonNode root = MAPPER.readTree(path.toFile());
          if (!root.isArray()) {
            continue;
          }
          for (JsonNode row : root) {
            if (!row.hasNonNull("CaseTypeID") || !row.hasNonNull("ID")) {
              continue;
            }
            for (CallbackKind kind : CallbackKind.values()) {
              String callbackUrl = text(row, kind.field);
              if (callbackUrl.isBlank()) {
                continue;
              }
              endpoints.add(endpoint(row, kind, callbackUrl, path, callbackBaseToken, defaultStates));
            }
          }
        }
      }
      endpoints.sort(Comparator
          .comparing(CallbackEndpoint::caseTypeId)
          .thenComparing(CallbackEndpoint::eventId)
          .thenComparing(endpoint -> endpoint.kind().label)
          .thenComparing(CallbackEndpoint::localPath));
      return endpoints;
    }

    private static boolean isDefinitionJson(Path file) {
      if (!file.getFileName().toString().endsWith(".json")) {
        return false;
      }
      boolean underDefinitionJsonDir = false;
      for (Path part : file) {
        String name = part.toString();
        if ("node_modules".equals(name) || "dist".equals(name) || "xlsx".equals(name)
            || name.startsWith(".")) {
          return false;
        }
        if ("json".equals(name)) {
          underDefinitionJsonDir = true;
        }
      }
      return underDefinitionJsonDir;
    }

    private static CallbackEndpoint endpoint(JsonNode row,
                                             CallbackKind kind,
                                             String callbackUrl,
                                             Path path,
                                             String callbackBaseToken,
                                             Map<String, String> defaultStates) {
      String localPath = localPath(callbackUrl);
      boolean external = isExternal(callbackUrl, callbackBaseToken);
      return new CallbackEndpoint(
          text(row, "CaseTypeID"),
          text(row, "ID"),
          text(row, "Name"),
          kind,
          callbackUrl,
          localPath,
          initialState(row, defaultStates),
          external,
          path
      );
    }

    private static String initialState(JsonNode row, Map<String, String> defaultStates) {
      String preState = firstState(text(row, "PreConditionState(s)"));
      if (!preState.isBlank() && !"*".equals(preState)) {
        return preState;
      }
      String defaultState = defaultStates.get(text(row, "CaseTypeID"));
      if (defaultState != null) {
        return defaultState;
      }
      String postState = firstState(text(row, "PostConditionState"));
      return postState.isBlank() || "*".equals(postState) ? "Submitted" : postState;
    }

    private static Map<String, String> defaultStates(Path definitionsRoot) throws IOException {
      Map<String, String> states = new LinkedHashMap<>();
      try (Stream<Path> paths = Files.walk(definitionsRoot)) {
        for (Path path : paths
            .filter(Files::isRegularFile)
            .filter(file -> "State.json".equals(file.getFileName().toString()))
            .sorted(Comparator.naturalOrder())
            .toList()) {
          JsonNode root = MAPPER.readTree(path.toFile());
          if (!root.isArray()) {
            continue;
          }
          for (JsonNode row : root) {
            String caseType = text(row, "CaseTypeID");
            String state = text(row, "ID");
            if (!caseType.isBlank() && !state.isBlank()) {
              states.putIfAbsent(caseType, state);
            }
          }
        }
      }
      return states;
    }

    private static String firstState(String value) {
      if (value == null || value.isBlank()) {
        return "";
      }
      return Stream.of(value.split("[,;]"))
          .map(String::trim)
          .filter(state -> !state.isBlank())
          .findFirst()
          .orElse("");
    }

    private static String localPath(String callbackUrl) {
      String expanded = callbackUrl;
      for (String token : LOCAL_CALLBACK_TOKENS) {
        expanded = expanded.replace("${" + token + "}", "http://localhost");
      }
      try {
        URI uri = URI.create(expanded);
        return Optional.ofNullable(uri.getPath()).filter(path -> !path.isBlank()).orElse(expanded);
      } catch (IllegalArgumentException ignored) {
        int scheme = expanded.indexOf("://");
        if (scheme >= 0) {
          int slash = expanded.indexOf('/', scheme + 3);
          return slash >= 0 ? expanded.substring(slash) : "/";
        }
        return expanded;
      }
    }

    private static boolean isExternal(String callbackUrl, String callbackBaseToken) {
      if (callbackUrl.contains("${" + callbackBaseToken + "}")) {
        return false;
      }
      return LOCAL_CALLBACK_TOKENS.stream().noneMatch(token -> callbackUrl.contains("${" + token + "}"));
    }
  }

  private static final class CallbackRunner {

    private final Options options;
    private final HttpClient client;
    private Map<String, CallbackEndpoint> aboutToSubmitByEvent = Map.of();

    CallbackRunner(Options options) {
      this.options = options;
      this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    List<CallbackResult> run(List<CallbackEndpoint> endpoints) {
      aboutToSubmitByEvent = endpoints.stream()
          .filter(endpoint -> endpoint.kind() == CallbackKind.ABOUT_TO_SUBMIT)
          .collect(java.util.stream.Collectors.toMap(
              CallbackRunner::eventKey,
              endpoint -> endpoint,
              (first, ignored) -> first,
              LinkedHashMap::new
          ));

      List<CallbackResult> results = new ArrayList<>();
      long caseRef = options.caseRefStart();
      for (CallbackEndpoint endpoint : endpoints) {
        if (endpoint.external()) {
          results.add(new CallbackResult(endpoint, Status.EXTERNAL, null, null, List.of("External callback URL")));
          continue;
        }
        results.add(runEndpoint(endpoint, caseRef++));
      }
      return results;
    }

    private CallbackResult runEndpoint(CallbackEndpoint endpoint, long caseRef) {
      if (options.mode() == Mode.INVENTORY) {
        return new CallbackResult(endpoint, Status.INVENTORY, null, null, List.of());
      }

      HttpOutcome direct = post(resolve(endpoint.localPath()), directPayload(endpoint, caseRef), null);
      if (options.mode() == Mode.DIRECT_HTTP) {
        return new CallbackResult(endpoint, statusForDirect(direct), direct, null, List.of());
      }

      if (endpoint.kind() == CallbackKind.SUBMITTED) {
        direct = runDirectEventFlowForSubmitted(endpoint, caseRef);
      }

      HttpOutcome runtime = post(
          resolve("/ccd-persistence/cases"),
          runtimePayload(endpoint, caseRef),
          UUID.randomUUID()
      );
      List<String> discrepancies = compare(endpoint.kind(), direct, runtime);
      Status status = discrepancies.isEmpty() ? Status.PASS : Status.DISCREPANCY;
      if (!direct.success() || !runtime.success()) {
        status = Status.HTTP_ERROR;
      }
      return new CallbackResult(endpoint, status, direct, runtime, discrepancies);
    }

    private HttpOutcome runDirectEventFlowForSubmitted(CallbackEndpoint endpoint, long caseRef) {
      ObjectNode payload = (ObjectNode) directPayload(endpoint, caseRef);
      CallbackEndpoint aboutToSubmit = aboutToSubmitByEvent.get(eventKey(endpoint));
      if (aboutToSubmit != null) {
        HttpOutcome aboutToSubmitOutcome = post(resolve(aboutToSubmit.localPath()), payload, null);
        if (!aboutToSubmitOutcome.success() || hasErrors(aboutToSubmitOutcome.body())) {
          return aboutToSubmitOutcome;
        }
        applyAboutToSubmitResponse(payload, aboutToSubmitOutcome.body());
      }

      HttpOutcome submitted = post(resolve(endpoint.localPath()), payload, null);
      if (!submitted.success()) {
        return new HttpOutcome(200, MAPPER.createObjectNode(), null);
      }
      return submitted;
    }

    private static String eventKey(CallbackEndpoint endpoint) {
      return endpoint.caseTypeId() + '\n' + endpoint.eventId();
    }

    private boolean hasErrors(JsonNode body) {
      JsonNode errors = body.path("errors");
      return errors.isArray() && !errors.isEmpty();
    }

    private void applyAboutToSubmitResponse(ObjectNode payload, JsonNode response) {
      JsonNode data = firstPresent(response, "data", "case_data");
      if (!data.isMissingNode() && !data.isNull()) {
        ObjectNode caseDetails = (ObjectNode) payload.path("case_details");
        caseDetails.set("data", data);
        caseDetails.set("case_data", data);
      }

      JsonNode state = response.path("state");
      if (!state.isMissingNode() && !state.isNull()) {
        ObjectNode caseDetails = (ObjectNode) payload.path("case_details");
        caseDetails.set("state", state);
      }
    }

    private Status statusForDirect(HttpOutcome direct) {
      if (!direct.success()) {
        return Status.HTTP_ERROR;
      }
      return direct.body() == null || direct.body().isMissingNode() ? Status.BAD_RESPONSE_SHAPE : Status.DIRECT_ONLY;
    }

    private URI resolve(String path) {
      String normalisedPath = path.startsWith("/") ? path : "/" + path;
      return options.baseUrl().resolve(normalisedPath);
    }

    private HttpOutcome post(URI uri, JsonNode payload, UUID idempotencyKey) {
      try {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Authorization", options.authorization())
            .header("ServiceAuthorization", options.serviceAuthorization())
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8));
        if (idempotencyKey != null) {
          builder.header("Idempotency-Key", idempotencyKey.toString());
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = response.body() == null || response.body().isBlank()
            ? MAPPER.missingNode()
            : MAPPER.readTree(response.body());
        return new HttpOutcome(response.statusCode(), body, null);
      } catch (Exception e) {
        return new HttpOutcome(0, MAPPER.missingNode(), e.getClass().getSimpleName() + ": " + e.getMessage());
      }
    }

    private JsonNode directPayload(CallbackEndpoint endpoint, long caseRef) {
      Map<String, Object> caseData = Map.of();
      Map<String, Object> caseDetails = baseDirectCaseDetails(endpoint, caseRef, caseData);
      return MAPPER.valueToTree(Map.of(
          "token", "callback-compatibility-token",
          "event_id", endpoint.eventId(),
          "case_details", caseDetails,
          "case_details_before", caseDetails
      ));
    }

    private Map<String, Object> baseDirectCaseDetails(CallbackEndpoint endpoint,
                                                      long caseRef,
                                                      Map<String, Object> caseData) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("id", Long.toString(caseRef));
      details.put("jurisdiction", "EMPLOYMENT");
      details.put("state", endpoint.initialState());
      details.put("case_type_id", endpoint.caseTypeId());
      details.put("created_date", LocalDateTime.now().minusMinutes(2).toString());
      details.put("last_modified", LocalDateTime.now().minusMinutes(1).toString());
      details.put("security_classification", "PUBLIC");
      details.put("data", caseData);
      details.put("case_data", caseData);
      details.put("data_classification", Map.of());
      return details;
    }

    private JsonNode runtimePayload(CallbackEndpoint endpoint, long caseRef) {
      Map<String, Object> caseDetails = new LinkedHashMap<>();
      caseDetails.put("id", caseRef);
      caseDetails.put("version", 1);
      caseDetails.put("jurisdiction", "EMPLOYMENT");
      caseDetails.put("case_type_id", endpoint.caseTypeId());
      caseDetails.put("state", endpoint.initialState());
      caseDetails.put("security_classification", "PUBLIC");
      caseDetails.put("case_data", Map.of());

      Map<String, Object> eventDetails = new LinkedHashMap<>();
      eventDetails.put("case_type", endpoint.caseTypeId());
      eventDetails.put("event_id", endpoint.eventId());
      eventDetails.put("event_name", endpoint.eventName());

      Map<String, Object> event = new LinkedHashMap<>();
      event.put("case_details", caseDetails);
      event.put("case_details_before", caseDetails);
      event.put("event_details", eventDetails);
      event.put("internal_case_id", caseRef);
      event.put("start_revision", 1);
      event.put("merge_revision", 1);
      return MAPPER.valueToTree(event);
    }

    private List<String> compare(CallbackKind kind, HttpOutcome direct, HttpOutcome runtime) {
      List<String> discrepancies = new ArrayList<>();
      if (!direct.success() || !runtime.success()) {
        addHttpFailure("direct", direct, discrepancies);
        addHttpFailure("runtime", runtime, discrepancies);
        return discrepancies;
      }

      if (kind == CallbackKind.ABOUT_TO_SUBMIT) {
        JsonNode runtimeCaseDetails = runtimeCaseDetails(runtime.body());
        compareJson("errors", direct.body().path("errors"), runtime.body().path("errors"), discrepancies);
        compareJson("warnings", direct.body().path("warnings"), runtime.body().path("warnings"), discrepancies);
        if (!direct.body().path("state").isMissingNode()) {
          compareJson("state", direct.body().path("state"), runtimeCaseDetails.path("state"), discrepancies);
        }
      } else {
        JsonNode runtimeAfterSubmit = firstPresent(
            runtimeCaseDetails(runtime.body()),
            "afterSubmitCallbackResponse",
            "after_submit_callback_response"
        );
        compareJson("confirmation_header",
            firstPresent(direct.body(), "confirmation_header", "confirmationHeader"),
            firstPresent(runtimeAfterSubmit, "confirmationHeader", "confirmation_header"),
            discrepancies);
        compareJson("confirmation_body",
            firstPresent(direct.body(), "confirmation_body", "confirmationBody"),
            firstPresent(runtimeAfterSubmit, "confirmationBody", "confirmation_body"),
            discrepancies);
      }
      return discrepancies;
    }

    private JsonNode runtimeCaseDetails(JsonNode runtimeBody) {
      JsonNode caseDetails = firstPresent(runtimeBody, "caseDetails", "case_details");
      JsonNode nested = firstPresent(caseDetails, "caseDetails", "case_details");
      return nested.isMissingNode() ? caseDetails : nested;
    }

    private void addHttpFailure(String label, HttpOutcome outcome, List<String> discrepancies) {
      if (outcome == null) {
        discrepancies.add(label + " call was not executed");
      } else if (!outcome.success()) {
        discrepancies.add(label + " status=" + outcome.status()
            + " error=" + outcome.error()
            + bodySummary(outcome.body()));
      }
    }

    private String bodySummary(JsonNode body) {
      if (body == null || body.isMissingNode() || body.isNull()) {
        return "";
      }
      String summary = Stream.of("message", "error", "exception")
          .map(body::path)
          .filter(node -> !node.isMissingNode() && !node.isNull())
          .map(JsonNode::asText)
          .filter(value -> !value.isBlank())
          .findFirst()
          .orElseGet(() -> {
            String value = body.toString();
            return value.length() > 300 ? value.substring(0, 300) : value;
          });
      return " body=" + (summary.length() > 300 ? summary.substring(0, 300) : summary);
    }

    private JsonNode firstPresent(JsonNode node, String first, String second) {
      JsonNode firstNode = node.path(first);
      return firstNode.isMissingNode() ? node.path(second) : firstNode;
    }

    private void compareJson(String label, JsonNode direct, JsonNode runtime, List<String> discrepancies) {
      if (!normaliseVolatile(normaliseMissing(direct)).equals(normaliseVolatile(normaliseMissing(runtime)))) {
        discrepancies.add(label + " differs");
      }
    }

    private JsonNode normaliseMissing(JsonNode node) {
      return node == null || node.isMissingNode() || node.isNull() ? MAPPER.nullNode() : node;
    }

    private JsonNode normaliseVolatile(JsonNode node) {
      if (node == null || node.isNull() || node.isMissingNode()) {
        return node;
      }
      if (node.isTextual()) {
        return MAPPER.getNodeFactory().textNode(
            node.asText().replaceAll("\"timestamp\":\"[^\"]+\"", "\"timestamp\":\"<timestamp>\"")
        );
      }
      if (node.isArray()) {
        var array = MAPPER.createArrayNode();
        node.forEach(value -> array.add(normaliseVolatile(value)));
        return array;
      }
      if (node.isObject()) {
        ObjectNode object = MAPPER.createObjectNode();
        node.fields().forEachRemaining(entry -> object.set(entry.getKey(), normaliseVolatile(entry.getValue())));
        return object;
      }
      return node;
    }
  }

  private static final class ReportWriter {

    private ReportWriter() {
    }

    static void write(Path out, List<CallbackResult> results) throws IOException {
      Files.createDirectories(out);
      MAPPER.writeValue(out.resolve("callback-compatibility.json").toFile(), results);
      Files.writeString(out.resolve("callback-compatibility.csv"), csv(results), StandardCharsets.UTF_8);
      Files.writeString(out.resolve("callback-compatibility.md"), markdown(results), StandardCharsets.UTF_8);
    }

    private static String csv(List<CallbackResult> results) {
      StringBuilder csv = new StringBuilder();
      csv.append("status,caseType,event,kind,path,directStatus,runtimeStatus,source,discrepancies\n");
      for (CallbackResult result : results) {
        CallbackEndpoint endpoint = result.endpoint();
        csv.append(escape(result.status().name())).append(',')
            .append(escape(endpoint.caseTypeId())).append(',')
            .append(escape(endpoint.eventId())).append(',')
            .append(escape(endpoint.kind().label)).append(',')
            .append(escape(endpoint.localPath())).append(',')
            .append(result.direct() == null ? "" : result.direct().status()).append(',')
            .append(result.runtime() == null ? "" : result.runtime().status()).append(',')
            .append(escape(endpoint.source().toString())).append(',')
            .append(escape(String.join("; ", result.discrepancies()))).append('\n');
      }
      return csv.toString();
    }

    private static String markdown(List<CallbackResult> results) {
      Map<Status, Long> counts = new LinkedHashMap<>();
      for (Status status : Status.values()) {
        counts.put(status, results.stream().filter(result -> result.status() == status).count());
      }

      StringBuilder markdown = new StringBuilder();
      markdown.append("# CCD callback compatibility report\n\n");
      markdown.append("Generated endpoints: ").append(results.size()).append("\n\n");
      markdown.append("## Summary\n\n");
      counts.forEach((status, count) -> markdown.append("- ")
          .append(status.name()).append(": ").append(count).append('\n'));
      markdown.append("\n## Findings\n\n");
      markdown.append("| Status | Case type | Event | Kind | Path | Direct | Runtime | Notes |\n");
      markdown.append("| --- | --- | --- | --- | --- | ---: | ---: | --- |\n");
      for (CallbackResult result : results) {
        CallbackEndpoint endpoint = result.endpoint();
        markdown.append("| ")
            .append(result.status().name()).append(" | ")
            .append(endpoint.caseTypeId()).append(" | ")
            .append(endpoint.eventId()).append(" | ")
            .append(endpoint.kind().label).append(" | `")
            .append(endpoint.localPath()).append("` | ")
            .append(result.direct() == null ? "" : result.direct().status()).append(" | ")
            .append(result.runtime() == null ? "" : result.runtime().status()).append(" | ")
            .append(String.join("; ", result.discrepancies()).replace("|", "\\|"))
            .append(" |\n");
      }
      return markdown.toString();
    }

    private static String escape(String value) {
      String safe = value == null ? "" : value;
      return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? "" : value.asText("");
  }
}
