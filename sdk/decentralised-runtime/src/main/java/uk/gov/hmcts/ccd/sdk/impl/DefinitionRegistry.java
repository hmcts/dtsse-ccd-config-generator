package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.domain.model.definition.CaseTypeDefinition;

@Component
@Slf4j
public class DefinitionRegistry {

  private final ObjectMapper mapper;
  private final PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();
  private Map<String, CaseTypeDefinition> definitions = Map.of();

  DefinitionRegistry(@Qualifier("ccd_mapper") ObjectMapper definitionMapper) {
    this.mapper = definitionMapper;
  }

  public Optional<CaseTypeDefinition> find(String caseTypeId) {
    return Optional.ofNullable(loadDefinitions().get(caseTypeId));
  }

  /**
   * Lazily loads case type definitions from the generated snapshot directory.
   */
  @SneakyThrows
  public synchronized Map<String, CaseTypeDefinition> loadDefinitions() {
    if (!this.definitions.isEmpty()) {
      return this.definitions;
    }

    var loaded = new HashMap<String, CaseTypeDefinition>();
    String configuredSnapshotDirectory = System.getProperty(
        "ccd.definition.snapshots.dir",
        System.getenv("CCD_DEFINITION_SNAPSHOTS_DIR")
    );
    if (configuredSnapshotDirectory != null && !configuredSnapshotDirectory.isBlank()) {
      loadDefinitionsFromDirectory(loaded, new File(configuredSnapshotDirectory));
    }
    loadDefinitionsFromDirectory(loaded, new File("build/cftlib/definition-snapshots"));
    loadDefinitionsFromDirectory(loaded, new File("/opt/app/build/cftlib/definition-snapshots"));
    loadDefinitionsFromClasspath(loaded);

    if (loaded.isEmpty()) {
      log.warn("DefinitionRegistry: no definition snapshots found");
      this.definitions = Map.of();
      return this.definitions;
    }

    this.definitions = Map.copyOf(loaded);
    log.info("DefinitionRegistry loaded {} case type definitions", this.definitions.size());
    return this.definitions;
  }

  @SneakyThrows
  private void loadDefinitionsFromDirectory(Map<String, CaseTypeDefinition> loaded, File directory) {
    File[] jsonFiles = directory.listFiles((dir, name) -> name.endsWith(".json"));
    if (jsonFiles == null) {
      return;
    }

    for (File file : jsonFiles) {
      loaded.put(caseTypeId(file.getName()), mapper.readValue(file, CaseTypeDefinition.class));
    }
  }

  @SneakyThrows
  private void loadDefinitionsFromClasspath(Map<String, CaseTypeDefinition> loaded) {
    for (Resource resource : resourceResolver.getResources("classpath*:definition-snapshots/*.json")) {
      String filename = resource.getFilename();
      if (filename != null) {
        loaded.put(caseTypeId(filename), mapper.readValue(resource.getInputStream(), CaseTypeDefinition.class));
      }
    }
  }

  private String caseTypeId(String filename) {
    return filename.substring(0, filename.lastIndexOf("."));
  }
}
