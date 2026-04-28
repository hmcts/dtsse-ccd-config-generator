package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.ResourcePatternResolver;
import uk.gov.hmcts.ccd.domain.model.definition.CaseTypeDefinition;

class DefinitionRegistryTest {

  private static final String CASE_TYPE_ID = "ET_EnglandWales";

  private final ObjectMapper mapper = new ObjectMapper();

  @TempDir
  Path tempDir;

  @Test
  void loadsDefinitionsFromFilesystemDirectory() throws Exception {
    Path snapshotFile = tempDir.resolve(CASE_TYPE_ID + ".json");
    Files.writeString(snapshotFile, mapper.writeValueAsString(new CaseTypeDefinition()));

    DefinitionRegistry registry = new DefinitionRegistry(
        mapper,
        List.of(tempDir),
        mock(ResourcePatternResolver.class)
    );

    assertThat(registry.find(CASE_TYPE_ID)).isPresent();
  }

  @Test
  void fallsBackToClasspathResourcesWhenFilesystemDirectoryIsEmpty() throws Exception {
    ResourcePatternResolver resourceResolver = mock(ResourcePatternResolver.class);
    when(resourceResolver.getResources(DefinitionRegistry.SNAPSHOT_CLASSPATH_PATTERN))
        .thenReturn(new ByteArrayResource[] {resource(CASE_TYPE_ID + ".json")});

    DefinitionRegistry registry = new DefinitionRegistry(
        mapper,
        List.of(tempDir),
        resourceResolver
    );

    assertThat(registry.find(CASE_TYPE_ID)).isPresent();
  }

  private ByteArrayResource resource(String filename) throws Exception {
    byte[] payload = mapper.writeValueAsBytes(new CaseTypeDefinition());
    return new ByteArrayResource(payload) {
      @Override
      public String getFilename() {
        return filename;
      }
    };
  }
}
