package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.domain.model.definition.CaseTypeDefinition;

class DefinitionRegistryTest {

  @TempDir
  Path tempDir;

  @Test
  void shouldLoadDefinitionsFromConfiguredSnapshotDirectory() throws Exception {
    var mapper = mock(ObjectMapper.class);
    var definition = mock(CaseTypeDefinition.class);
    var snapshot = Files.createFile(tempDir.resolve("TEST_CASE.json"));
    when(mapper.readValue(snapshot.toFile(), CaseTypeDefinition.class)).thenReturn(definition);

    var registry = new DefinitionRegistry(mapper, tempDir.toString());

    assertThat(registry.find("TEST_CASE")).containsSame(definition);
    assertThat(registry.find("TEST_CASE")).containsSame(definition);
    verify(mapper).readValue(snapshot.toFile(), CaseTypeDefinition.class);
    verifyNoMoreInteractions(mapper);
  }

  @Test
  void shouldReturnEmptyWhenSnapshotDirectoryDoesNotContainDefinitions() {
    var mapper = mock(ObjectMapper.class);
    var registry = new DefinitionRegistry(mapper, tempDir.resolve("missing").toString());

    assertThat(registry.find("UNKNOWN_CASE")).isEmpty();
    verifyNoMoreInteractions(mapper);
  }
}
