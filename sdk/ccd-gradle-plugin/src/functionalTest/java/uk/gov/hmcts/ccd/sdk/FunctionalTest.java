package uk.gov.hmcts.ccd.sdk;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

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
  public void testGradle8MinJava21() {
    checkTestProject("8.4");
  }

  @Test
  public void versionlessLibrariesWithApplicationIndexing() throws IOException {
    checkSdkDependencies(false, "-PapplicationIndexing");
  }

  @Test
  public void versionlessRuntimeOnlyLibraryWithCftlibAppliedFirst() throws IOException {
    checkSdkDependencies(false, "-PcftlibFirst", "-PruntimeOnlyLibrary");
  }

  @Test
  public void legacyFlagsWarnAndKeepLocalIndexing() throws IOException {
    checkSdkDependencies(true);
  }

  @Test
  public void legacyFlagsWarnAndKeepApplicationIndexing() throws IOException {
    checkSdkDependencies(true, "-PcftlibFirst", "-PapplicationIndexing");
  }

  private void checkSdkDependencies(boolean legacy, String... options) throws IOException {
    FileUtils.copyDirectory(new File("test-projects/sdk-bom"), testProjectDir.getRoot());
    var arguments = new ArrayList<>(Arrays.asList("verifySdkDependencies", "--stacktrace"));
    arguments.addAll(Arrays.asList(options));
    if (legacy) {
      arguments.add("-Plegacy");
    }
    var result = GradleRunner.create()
        .withPluginClasspath()
        .withProjectDir(testProjectDir.getRoot())
        .withArguments(arguments)
        .withGradleVersion("8.4")
        .build();
    assertEquals(TaskOutcome.SUCCESS, result.task(":verifySdkDependencies").getOutcome());
    for (String flag : Arrays.asList("decentralised", "runtimeIndexing", "caseEventServiceBus")) {
      if (legacy) {
        assertTrue(result.getOutput().contains("ccd." + flag + " is deprecated."));
      } else {
        assertFalse(result.getOutput().contains("ccd." + flag + " is deprecated."));
      }
    }
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
