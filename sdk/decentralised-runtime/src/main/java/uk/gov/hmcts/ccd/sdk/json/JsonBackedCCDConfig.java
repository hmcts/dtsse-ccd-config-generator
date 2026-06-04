package uk.gov.hmcts.ccd.sdk.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Webhook;
import uk.gov.hmcts.ccd.sdk.impl.json.JsonCallbackAdapterFactory;

@RequiredArgsConstructor
public class JsonBackedCCDConfig<Case, State, Role extends HasRole> implements CCDConfig<Case, State, Role> {

  private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() {};

  @NonNull
  private final String caseTypeId;
  @NonNull
  private final String jsonRoot;
  @NonNull
  private final ResourceLoader resourceLoader;
  @NonNull
  private final ObjectMapper mapper;
  @NonNull
  private final JsonCallbackAdapterFactory callbackAdapterFactory;

  @Override
  public String groupingKey() {
    return caseTypeId;
  }

  @Override
  public void configure(ConfigBuilder<Case, State, Role> builder) {
    Map<String, Object> caseType = requiredRow("CaseType", "ID", caseTypeId);
    String jurisdictionId = string(caseType, "JurisdictionID");

    builder.caseType(caseTypeId, caseTypeId, caseTypeId);
    builder.jurisdiction(jurisdictionId, jurisdictionId, jurisdictionId);

    for (Map<String, Object> event : readFolder("CaseEvent")) {
      if (caseTypeId.equals(string(event, "CaseTypeID"))) {
        configureEvent(builder, event);
      }
    }
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
          callbackAdapterFactory.validate(callbackUrl);
          event.aboutToSubmitCallback(callbackAdapterFactory.aboutToSubmit(callbackUrl, id));
        });
    url(definition, "CallBackURLSubmittedEvent")
        .ifPresent(callbackUrl -> {
          callbackAdapterFactory.validate(callbackUrl);
          event.submittedCallback(callbackAdapterFactory.submitted(callbackUrl, id));
        });
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
        values.addAll(mapper.readValue(input, ROWS));
      }
    }
    return values;
  }

  private List<Resource> resources(String folder) {
    String base = jsonRoot.endsWith("/") ? jsonRoot + folder : jsonRoot + "/" + folder;
    Resource folderResource = resourceLoader.getResource(base);
    if (folderResource.exists()) {
      try {
        if (folderResource.isFile()) {
          return fileResources(folderResource.getFile().toPath());
        }
      } catch (IOException e) {
        throw new IllegalStateException("Unable to inspect " + folderResource.getDescription(), e);
      }
    }

    Resource fileResource = resourceLoader.getResource(base + ".json");
    return fileResource.exists() ? List.of(fileResource) : List.of();
  }

  private List<Resource> fileResources(Path path) throws IOException {
    if (Files.isRegularFile(path)) {
      return List.of(resourceLoader.getResource(path.toUri().toString()));
    }

    try (var stream = Files.list(path)) {
      return stream
          .filter(candidate -> Files.isRegularFile(candidate) && candidate.toString().endsWith(".json"))
          .sorted()
          .map(candidate -> resourceLoader.getResource(candidate.toUri().toString()))
          .toList();
    }
  }

  private Optional<String> url(Map<String, Object> row, String column) {
    String value = string(row, column);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(value.trim());
  }

  private String string(Map<String, Object> row, String column) {
    Object value = row.get(column);
    return value == null ? null : value.toString();
  }
}
