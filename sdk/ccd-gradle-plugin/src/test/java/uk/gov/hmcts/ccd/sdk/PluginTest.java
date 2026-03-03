package uk.gov.hmcts.ccd.sdk;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;


import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Test;

public class PluginTest {
  @Test
  public void pluginRegistersATask() {
    Project project = ProjectBuilder.builder().build();
    project.getPlugins().apply("hmcts.ccd.sdk");

    assertNotNull(project.getTasks().findByName("generateCCDConfig"));
    CcdSdkPlugin.CCDConfig config = (CcdSdkPlugin.CCDConfig) project.getExtensions().getByName("ccd");
    assertNotNull(config.getTsBindings());
    assertFalse(config.getTsBindings().isEnabled());
  }

}
