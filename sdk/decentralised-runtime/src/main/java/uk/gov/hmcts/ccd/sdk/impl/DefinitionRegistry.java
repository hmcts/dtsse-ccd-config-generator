package uk.gov.hmcts.ccd.sdk.impl;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.ccd.domain.model.definition.CaseTypeDefinition;

@Component
@Slf4j
class DefinitionRegistry {

  private final ObjectMapper mapper;
  private final File snapshotDirectory;
  private Map<String, CaseTypeDefinition> definitions = Map.of();

  @Autowired
  DefinitionRegistry(@Qualifier("ccd_mapper") ObjectMapper definitionMapper) {
    this(definitionMapper, new File("build/cftlib/definition-snapshots"));
  }

  DefinitionRegistry(ObjectMapper definitionMapper, File snapshotDirectory) {
    this.mapper = definitionMapper;
    this.snapshotDirectory = snapshotDirectory;
  }

  Optional<CaseTypeDefinition> find(String caseTypeId) {
    return Optional.ofNullable(loadDefinitions().get(caseTypeId));
  }

  void requireDefinitions() {
    if (loadDefinitions().isEmpty()) {
      throw new IllegalStateException(
          "CCD messaging is enabled but no definition snapshots were found in "
              + snapshotDirectory.getAbsolutePath()
      );
    }
  }

  /**
   * Lazily loads case type definitions from the generated snapshot directory.
   */
  @SneakyThrows
  synchronized Map<String, CaseTypeDefinition> loadDefinitions() {
    if (!this.definitions.isEmpty()) {
      return this.definitions;
    }
    var loaded = new HashMap<String, CaseTypeDefinition>();
    File[] jsonFiles = snapshotDirectory.listFiles((dir, name) -> name.endsWith(".json"));

    if (jsonFiles != null) {
      for (File file : jsonFiles) {
        String fileNameWithoutExtension = file.getName().substring(0, file.getName().lastIndexOf("."));
        CaseTypeDefinition definition = mapper.readValue(file, CaseTypeDefinition.class);
        loaded.put(fileNameWithoutExtension, definition);
      }
    }

    if (loaded.isEmpty()) {
      log.warn("DefinitionRegistry: no definition snapshots found");
      this.definitions = Map.of();
      return this.definitions;
    }

    this.definitions = Map.copyOf(loaded);
    log.info("DefinitionRegistry loaded {} case type definitions", this.definitions.size());
    return this.definitions;
  }
}
