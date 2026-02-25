package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.domain.model.definition.CaseTypeDefinition;

@Component
@Slf4j
class DefinitionRegistry {

  private final ObjectMapper mapper;
  private final String snapshotDir;
  private Map<String, CaseTypeDefinition> definitions = Map.of();

  DefinitionRegistry(
      @Qualifier("ccd_mapper") ObjectMapper definitionMapper,
      @Value("${ccd.definition.snapshot-dir:"
          + "build/cftlib/definition-snapshots}")
      String snapshotDir
  ) {
    this.mapper = definitionMapper;
    this.snapshotDir = snapshotDir;
  }

  Optional<CaseTypeDefinition> find(String caseTypeId) {
    return Optional.ofNullable(loadDefinitions().get(caseTypeId));
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
    File[] jsonFiles = new File(snapshotDir)
        .listFiles((dir, name) -> name.endsWith(".json"));

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
