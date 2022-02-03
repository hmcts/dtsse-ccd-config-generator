package uk.gov.hmcts.ccd.sdk;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.apache.commons.io.FileUtils;
import org.apache.groovy.util.Maps;
import org.gradle.api.Task;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
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
        .withGradleVersion("5.6.4");
    assertEquals(TaskOutcome.SUCCESS,r.build().task(":generateCCDConfig").getOutcome());
    File caseField = new File(testProjectDir.getRoot(), "build/ccd-definition/test/CaseField.json");
    assertTrue(caseField.exists());

    // Run a second time to ensure a non-clean build without changes is up to date.
    assertEquals(TaskOutcome.UP_TO_DATE,r.build().task(":generateCCDConfig").getOutcome());
  }

  public static GradleRunner runner(File project) {
    return GradleRunner.create()
        .forwardOutput()
        .withPluginClasspath()
        .withProjectDir(project)
        .withArguments("generateCCDConfig", "test", "-si");
  }
}

