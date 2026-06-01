package uk.gov.hmcts.ccd.sdk.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.ccd.sdk.api.Webhook;
import uk.gov.hmcts.ccd.sdk.impl.json.JsonCallbackAdapterFactory;

public class JsonBackedCCDConfig<Case, State, Role extends HasRole> implements CCDConfig<Case, State, Role> {

  private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() {};

  private final String caseTypeId;
  private final String jsonRoot;
  private final ResourceLoader resourceLoader;
  private final ObjectMapper mapper;
  private final JsonCallbackAdapterFactory callbackAdapterFactory;

  public JsonBackedCCDConfig(String caseTypeId,
                             String jsonRoot,
                             ResourceLoader resourceLoader,
                             ObjectMapper mapper,
                             JsonCallbackAdapterFactory callbackAdapterFactory) {
    this.caseTypeId = Objects.requireNonNull(caseTypeId);
    this.jsonRoot = Objects.requireNonNull(jsonRoot);
    this.resourceLoader = Objects.requireNonNull(resourceLoader);
    this.mapper = Objects.requireNonNull(mapper);
    this.callbackAdapterFactory = Objects.requireNonNull(callbackAdapterFactory);
  }

  @Override
  public void configure(ConfigBuilder<Case, State, Role> builder) {
    Map<String, Object> caseType = requiredRow("CaseType", "ID", caseTypeId);
    Map<String, Object> jurisdiction = requiredRow("Jurisdiction", "ID", string(caseType, "JurisdictionID"));
    List<Map<String, Object>> authorisationCaseEvents = optionalRows("AuthorisationCaseEvent");

    builder.caseType(caseTypeId, name(caseType), description(caseType));
    builder.jurisdiction(string(jurisdiction, "ID"), name(jurisdiction), description(jurisdiction));

    for (Map<String, Object> event : rows("CaseEvent")) {
      if (caseTypeId.equals(string(event, "CaseTypeID"))) {
        configureEvent(builder, event, authorisationCaseEvents);
      }
    }
  }

  private void configureEvent(ConfigBuilder<Case, State, Role> builder,
                              Map<String, Object> definition,
                              List<Map<String, Object>> authorisationCaseEvents) {
    String id = string(definition, "ID");
    Event.EventBuilder<Case, Role, State> event = builder.event(id)
        .forAllStates()
        .name(name(definition))
        .showSummary(booleanValue(definition, "ShowSummary"));

    event.description(string(definition, "Description"));
    if (booleanValue(definition, "ShowEventNotes")) {
      event.showEventNotes();
    }
    event.endButtonLabel(string(definition, "EndButtonLabel"));
    event.displayOrder(integer(definition, "DisplayOrder", -1));
    event.retries(Webhook.AboutToStart, string(definition, "RetriesTimeoutURLAboutToStartEvent"));
    event.retries(Webhook.AboutToSubmit, string(definition, "RetriesTimeoutURLAboutToSubmitEvent"));
    event.retries(Webhook.Submitted, string(definition, "RetriesTimeoutURLSubmittedEvent"));

    url(definition, "CallBackURLAboutToStartEvent")
        .map(callbackUrl -> callbackAdapterFactory.aboutToStart(callbackUrl, id))
        .ifPresent(event::aboutToStartCallback);
    url(definition, "CallBackURLAboutToSubmitEvent")
        .map(callbackUrl -> callbackAdapterFactory.aboutToSubmit(callbackUrl, id))
        .ifPresent(event::aboutToSubmitCallback);
    url(definition, "CallBackURLSubmittedEvent")
        .map(callbackUrl -> callbackAdapterFactory.submitted(callbackUrl, id))
        .ifPresent(event::submittedCallback);
    authorisationCaseEvents.stream()
        .filter(row -> caseTypeId.equals(string(row, "CaseTypeID")))
        .filter(row -> id.equals(string(row, "CaseEventID")))
        .forEach(row -> event.grant(permissions(string(row, "CRUD")), role(string(row, "UserRole"))));
  }

  private Map<String, Object> requiredRow(String folder, String idColumn, String id) {
    return rows(folder).stream()
        .filter(row -> id.equals(string(row, idColumn)))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No JSON " + folder + " row found for " + id));
  }

  private List<Map<String, Object>> rows(String folder) {
    return readFolder(folder, true);
  }

  private List<Map<String, Object>> optionalRows(String folder) {
    return readFolder(folder, false);
  }

  private List<Map<String, Object>> readFolder(String folder, boolean required) {
    List<Resource> resources = resources(folder);
    if (resources.isEmpty()) {
      if (!required) {
        return List.of();
      }
      throw new IllegalStateException("No JSON files found in " + jsonRoot + "/" + folder);
    }

    List<Map<String, Object>> values = new ArrayList<>();
    for (Resource resource : resources) {
      try (InputStream input = resource.getInputStream()) {
        values.addAll(mapper.readValue(input, ROWS));
      } catch (IOException e) {
        throw new IllegalStateException("Unable to read " + resource.getDescription(), e);
      }
    }
    return values;
  }

  @SuppressWarnings("unchecked")
  private Role role(String userRole) {
    Class<?> roleClass = ResolvableType.forClass(getClass())
        .as(JsonBackedCCDConfig.class)
        .getGeneric(2)
        .resolve();
    if (roleClass == null || roleClass.getEnumConstants() == null) {
      throw new IllegalStateException("Unable to resolve JSON-backed role enum for " + getClass().getName());
    }

    for (Role role : (Role[]) roleClass.getEnumConstants()) {
      if (role.getRole().equals(userRole)) {
        return role;
      }
    }
    throw new IllegalStateException("No role enum value found for JSON UserRole " + userRole);
  }

  private Set<Permission> permissions(String crud) {
    if (crud == null || crud.isBlank()) {
      return Set.of();
    }
    Set<Permission> permissions = EnumSet.noneOf(Permission.class);
    for (char value : crud.toCharArray()) {
      permissions.add(Permission.valueOf(String.valueOf(value)));
    }
    return permissions;
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

  private String name(Map<String, Object> row) {
    String name = string(row, "Name");
    return name == null || name.isBlank() ? string(row, "ID") : name;
  }

  private String description(Map<String, Object> row) {
    String description = string(row, "Description");
    return description == null || description.isBlank() ? name(row) : description;
  }

  private boolean booleanValue(Map<String, Object> row, String column) {
    String value = string(row, column);
    return "Y".equalsIgnoreCase(value) || "Yes".equalsIgnoreCase(value);
  }

  private int integer(Map<String, Object> row, String column, int fallback) {
    Object value = row.get(column);
    if (value instanceof Number number) {
      return number.intValue();
    }
    String text = string(row, column);
    return text == null || text.isBlank() ? fallback : Integer.parseInt(text);
  }

  private java.util.Optional<String> url(Map<String, Object> row, String column) {
    String value = string(row, column);
    if (value == null || value.isBlank()) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(value.trim());
  }

  private String string(Map<String, Object> row, String column) {
    Object value = row.get(column);
    return value == null ? null : value.toString();
  }
}
