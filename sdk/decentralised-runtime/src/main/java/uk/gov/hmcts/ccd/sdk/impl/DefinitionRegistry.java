package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.domain.model.definition.CaseTypeDefinition;

@Component
@Slf4j
class DefinitionRegistry {

  static final String SNAPSHOT_CLASSPATH_PATTERN = "classpath*:definition-snapshots/*.json";
  private static final List<Path> DEFAULT_SNAPSHOT_DIRECTORIES = List.of(
      Path.of("build", "cftlib", "definition-snapshots"),
      Path.of("/opt/app", "build", "cftlib", "definition-snapshots")
  );

  private final ObjectMapper mapper;
  private final List<Path> snapshotDirectories;
  private final ResourcePatternResolver resourceResolver;
  private Map<String, CaseTypeDefinition> definitions = Map.of();

  @Autowired
  DefinitionRegistry(@Qualifier("ccd_mapper") ObjectMapper definitionMapper) {
    this(
        definitionMapper,
        DEFAULT_SNAPSHOT_DIRECTORIES,
        new PathMatchingResourcePatternResolver()
    );
  }

  DefinitionRegistry(
      ObjectMapper definitionMapper,
      List<Path> snapshotDirectories,
      ResourcePatternResolver resourceResolver
  ) {
    this.mapper = definitionMapper;
    this.snapshotDirectories = snapshotDirectories;
    this.resourceResolver = resourceResolver;
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
    loadFromFilesystem(loaded);
    if (loaded.isEmpty()) {
      loadFromClasspath(loaded);
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

  private void loadFromFilesystem(Map<String, CaseTypeDefinition> loaded) throws IOException {
    for (Path snapshotDirectory : snapshotDirectories) {
      if (!Files.isDirectory(snapshotDirectory)) {
        continue;
      }
      try (var files = Files.list(snapshotDirectory)) {
        files
            .filter(path -> path.getFileName().toString().endsWith(".json"))
            .sorted()
            .forEach(path -> readFile(path, loaded));
      }
      if (!loaded.isEmpty()) {
        log.info("DefinitionRegistry loaded snapshots from filesystem directory {}", snapshotDirectory);
        return;
      }
    }
  }

  private void loadFromClasspath(Map<String, CaseTypeDefinition> loaded) throws IOException {
    Resource[] resources = resourceResolver.getResources(SNAPSHOT_CLASSPATH_PATTERN);
    Arrays.sort(resources, (left, right) -> resourceName(left).compareTo(resourceName(right)));
    for (Resource resource : resources) {
      readResource(resource, loaded);
    }
    if (!loaded.isEmpty()) {
      log.info("DefinitionRegistry loaded snapshots from classpath pattern {}", SNAPSHOT_CLASSPATH_PATTERN);
    }
  }

  @SneakyThrows
  private void readFile(Path path, Map<String, CaseTypeDefinition> loaded) {
    String filename = path.getFileName().toString();
    String fileNameWithoutExtension = filename.substring(0, filename.lastIndexOf('.'));
    loaded.put(fileNameWithoutExtension, mapper.readValue(path.toFile(), CaseTypeDefinition.class));
  }

  @SneakyThrows
  private void readResource(Resource resource, Map<String, CaseTypeDefinition> loaded) {
    String filename = resourceName(resource);
    if (!filename.endsWith(".json")) {
      return;
    }
    String fileNameWithoutExtension = filename.substring(0, filename.lastIndexOf('.'));
    try (InputStream inputStream = resource.getInputStream()) {
      loaded.put(fileNameWithoutExtension, mapper.readValue(inputStream, CaseTypeDefinition.class));
    }
  }

  private static String resourceName(Resource resource) {
    return Optional.ofNullable(resource.getFilename()).orElse("");
  }
}
