package uk.gov.hmcts.ccd.sdk;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertTrue;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FunctionalTest {

  @Rule
  public final TemporaryFolder testProjectDir = new TemporaryFolder();

  @Before
  public void setup() throws IOException {
    FileUtils.cleanDirectory(testProjectDir.getRoot());
    FileUtils.copyDirectory(new File("test-projects/java-library"),
      testProjectDir.getRoot());
  }

  //  https://docs.gradle.org/current/userguide/compatibility.html
  @Test
  public void testGradle7() {
    checkTestProject("7.3");
  }

  @Test
  public void testGradle8() {
    checkTestProject("8.0");
  }

  public void checkTestProject(String gradleVersion) {
    var r = GradleRunner.create()
        .forwardOutput()
        .withPluginClasspath()
        .withProjectDir(testProjectDir.getRoot())
        .withArguments("generateCCDConfig", "test", "-si")
      .withGradleVersion(gradleVersion);

    assertEquals(TaskOutcome.SUCCESS,r.build().task(":generateCCDConfig").getOutcome());
    File caseField = new File(testProjectDir.getRoot(), "build/ccd-definition/test/CaseField.json");
    assertTrue(caseField.exists());

    // Run a second time to ensure a non-clean build without changes is up to date.
    assertEquals(TaskOutcome.UP_TO_DATE,r.build().task(":generateCCDConfig").getOutcome());
  }
}

