package uk.gov.hmcts.ccd.sdk;

import static junit.framework.TestCase.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.apache.commons.io.FileUtils;
import org.apache.groovy.util.Maps;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FunctionalTest {

  @Rule
  public final TemporaryFolder testProjectDir = new TemporaryFolder();
  private File buildFile;

  @Before
  public void setup() throws IOException {
    buildFile = testProjectDir.newFile("build.gradle");
  }

  @Test
  public void testEmptyProject() throws IOException {
    FileUtils.copyDirectory(new File("test-projects/java-library"),
        testProjectDir.getRoot());
    GradleRunner r = runner(testProjectDir.getRoot())
        .withGradleVersion("4.10.3");
    r.build();
    File caseField = new File(testProjectDir.getRoot(), "build/ccd-definition/CaseField.json");
    assertTrue(caseField.exists());
    r.build(); // Run a second time to ensure a non-clean build succeeds.
    assertTrue(caseField.exists());
  }

  public static GradleRunner runner(File project) {
    return GradleRunner.create()
        .forwardOutput()
        .withPluginClasspath()
        .withProjectDir(project)
        .withArguments("generateCCDConfig", "test", "-si");
  }
}

