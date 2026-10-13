package uk.gov.hmcts.ccd.sdk.json;

import com.fasterxml.jackson.core.type.TypeReference;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import uk.gov.hmcts.ccd.sdk.ConfigBuilderImpl;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Webhook;

/**
 * Reads a case type's events and states from a CCD definition in JSON, laid out as ccd-definition-processor reads it:
 * one folder or file per sheet. A sheet's folder may hold JSON files in subfolders at any depth.
 *
 * <p>Files whose names match an excluded pattern are skipped, as ccd-definition-processor's {@code -e} option skips
 * them. A definition that keeps per-environment variants, such as {@code *-prod.json} and {@code *-nonprod.json},
 * excludes the variants that do not apply where it runs.
 */
public class JsonBackedCCDConfig<Case, State, Role extends HasRole>
    implements CCDConfig<Case, State, Role>, Ordered {

  private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() {};

  private final JsonCCDConfigSupport support;
  private final String caseTypeId;
  private final String jsonRoot;
  private final List<PathMatcher> excludedFiles;

  public JsonBackedCCDConfig(@NonNull JsonCCDConfigSupport support, @NonNull String caseTypeId,
                             @NonNull String jsonRoot) {
    this(support, caseTypeId, jsonRoot, List.of());
  }

  /**
   * Reads the definition at {@code jsonRoot}, skipping files whose names match any of the excluded patterns.
   *
   * @param excludedFilePatterns file name globs, for example {@code *-prod.json} or {@code UserProfile.json}
   */
  public JsonBackedCCDConfig(@NonNull JsonCCDConfigSupport support, @NonNull String caseTypeId,
                             @NonNull String jsonRoot, @NonNull List<String> excludedFilePatterns) {
    this.support = support;
    this.caseTypeId = caseTypeId;
    this.jsonRoot = jsonRoot;
    this.excludedFiles = excludedFilePatterns.stream()
        .map(String::trim)
        .filter(pattern -> !pattern.isEmpty())
        .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
        .toList();
  }

  @Override
  public Set<String> caseTypeIds() {
    return Set.of(caseTypeId);
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public void configure(ConfigBuilder<Case, State, Role> builder) {
    Map<String, Object> caseType = requiredRow("CaseType", "ID", caseTypeId);
    String jurisdictionId = string(caseType, "JurisdictionID");

    builder.caseType(caseTypeId, caseTypeId, caseTypeId);
    builder.jurisdiction(jurisdictionId, jurisdictionId, jurisdictionId);

    for (Map<String, Object> state : readOptionalFolder("State")) {
      if (belongsToCaseType(state)) {
        builder.stateLabel(string(state, "ID"), string(state, "Name"));
      }
    }

    for (Map<String, Object> event : readFolder("CaseEvent")) {
      if (belongsToCaseType(event)) {
        configureEvent(builder, event);
      }
    }

    Map<String, Event<Case, Role, State>> originalEvents = new HashMap<>();
    ((ConfigBuilderImpl<Case, State, Role>) builder).onEventReplaced((previous, replacement) ->
        requireCallbacksRetained(originalEvents.computeIfAbsent(previous.getId(), id -> previous),
            previous, replacement));
  }

  private void configureEvent(ConfigBuilder<Case, State, Role> builder,
                              Map<String, Object> definition) {
    String id = string(definition, "ID");
    Event.EventBuilder<Case, Role, State> event = builder.event(id)
        .forAllStates()
        .name(string(definition, "Name"))
        .description(string(definition, "Description"));

    event.retries(Webhook.Submitted, string(definition, "RetriesTimeoutURLSubmittedEvent"));

    url(definition, "CallBackURLAboutToSubmitEvent")
        .ifPresent(callbackUrl -> {
          support.callbackBridge().validate(callbackUrl);
          event.aboutToSubmitCallback(support.callbackBridge().aboutToSubmit(callbackUrl, id));
        });
    url(definition, "CallBackURLSubmittedEvent")
        .ifPresent(callbackUrl -> {
          support.callbackBridge().validate(callbackUrl);
          event.submittedCallback(support.callbackBridge().submitted(callbackUrl, id));
        });
  }

  private void requireCallbacksRetained(Event<Case, Role, State> original, Event<Case, Role, State> previous,
                                       Event<Case, Role, State> replacement) {
    // A decentralised submit handler replaces the callback lifecycle. Keep the JSON URLs for rolling deployments.
    if (replacement.hasSubmitHandler()) {
      return;
    }

    // The original requirements still apply after an intermediate decentralised replacement.
    boolean requiresAboutToSubmit = original.getAboutToSubmitCallback() != null
        || previous.getAboutToSubmitCallback() != null;
    boolean requiresSubmitted = original.getSubmittedCallback() != null || previous.getSubmittedCallback() != null;
    List<String> missing = new ArrayList<>();
    if (requiresAboutToSubmit && replacement.getAboutToSubmitCallback() == null) {
      missing.add("about-to-submit");
    }
    if (requiresSubmitted && replacement.getSubmittedCallback() == null) {
      missing.add("submitted");
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "Replacement for event '%s' in case type '%s' drops callbacks: %s"
              .formatted(previous.getId(), caseTypeId, String.join(", ", missing))
      );
    }
  }

  private Map<String, Object> requiredRow(String folder, String idColumn, String id) {
    return readFolder(folder).stream()
        .filter(row -> id.equals(string(row, idColumn)))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No JSON " + folder + " row found for " + id));
  }

  @SneakyThrows
  private List<Map<String, Object>> readFolder(String folder) {
    List<Resource> resources = resources(folder);
    if (resources.isEmpty()) {
      throw new IllegalStateException("No JSON files found in " + jsonRoot + "/" + folder);
    }

    List<Map<String, Object>> values = new ArrayList<>();
    for (Resource resource : resources) {
      try (InputStream input = resource.getInputStream()) {
        values.addAll(support.mapper().readValue(input, ROWS));
      }
    }
    return values;
  }

  @SneakyThrows
  private List<Map<String, Object>> readOptionalFolder(String folder) {
    List<Resource> resources = resources(folder);
    if (resources.isEmpty()) {
      return List.of();
    }

    List<Map<String, Object>> values = new ArrayList<>();
    for (Resource resource : resources) {
      try (InputStream input = resource.getInputStream()) {
        values.addAll(support.mapper().readValue(input, ROWS));
      }
    }
    return values;
  }

  @SneakyThrows
  private List<Resource> resources(String folder) {
    String base = jsonRoot.endsWith("/") ? jsonRoot + folder : jsonRoot + "/" + folder;
    ResourcePatternResolver resolver = ResourcePatternUtils.getResourcePatternResolver(support.resourceLoader());

    List<Resource> resources = Arrays.stream(folderResources(resolver, base))
        .filter(Resource::isReadable)
        .sorted(Comparator.comparing(JsonBackedCCDConfig::location))
        .toList();
    if (resources.isEmpty()) {
      Resource file = resolver.getResource(base + ".json");
      resources = file.exists() ? List.of(file) : List.of();
    }
    return resources.stream().filter(resource -> !excluded(resource)).toList();
  }

  private static Resource[] folderResources(ResourcePatternResolver resolver, String folder) throws IOException {
    try {
      return resolver.getResources(folder + "/**/*.json");
    } catch (FileNotFoundException e) {
      // A classpath sheet kept as a single file has no folder to search.
      return new Resource[0];
    }
  }

  private boolean excluded(Resource resource) {
    String name = resource.getFilename();
    return name != null && excludedFiles.stream().anyMatch(matcher -> matcher.matches(Path.of(name)));
  }

  @SneakyThrows
  private static String location(Resource resource) {
    return resource.getURL().toString();
  }

  private Optional<String> url(Map<String, Object> row, String column) {
    String value = string(row, column);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(value.trim());
  }

  private boolean belongsToCaseType(Map<String, Object> row) {
    String rowCaseTypeId = string(row, "CaseTypeID");
    return rowCaseTypeId == null || caseTypeId.equals(rowCaseTypeId);
  }

  private String string(Map<String, Object> row, String column) {
    Object value = row.get(column);
    return value == null ? null : value.toString();
  }
}
