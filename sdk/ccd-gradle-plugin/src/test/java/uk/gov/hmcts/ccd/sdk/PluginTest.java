package uk.gov.hmcts.ccd.sdk;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
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
  public void pluginAddsPdfLibDependencyWhenEnabled() {
    Project project = ProjectBuilder.builder().build();
    project.getPlugins().apply("hmcts.ccd.sdk");

    CcdSdkPlugin.CCDConfig config = project.getExtensions().getByType(CcdSdkPlugin.CCDConfig.class);
    config.setPdfLib(true);

    ((ProjectInternal) project).evaluate();

    boolean pdfLibPresent = project.getConfigurations()
        .getByName("implementation")
        .getDependencies()
        .stream()
        .anyMatch(dep -> "com.github.hmcts".equals(dep.getGroup())
            && "pdf-lib".equals(dep.getName()));

    assertTrue("pdf-lib dependency should be registered", pdfLibPresent);
  }

}
