package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertNotNull;


import com.google.common.collect.SetMultimap;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Test;

public class PluginTest {
  @Test
  public void pluginRegistersATask() {
    Project project = ProjectBuilder.builder().build();
    project.getPlugins().apply("hmcts.ccd.sdk");

    assertNotNull(project.getTasks().findByName("generateCCDConfig"));
  }

  @Test
  public void pluginExtractsGeneratorDependencies() {
    SetMultimap<String, String> deps =
        PomParser.getGeneratorDependencies();
    assertThat(deps.get("runtimeOnly")).size().isGreaterThan(1);
  }
}
