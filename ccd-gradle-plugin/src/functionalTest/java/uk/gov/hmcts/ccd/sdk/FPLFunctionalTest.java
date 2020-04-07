package uk.gov.hmcts.ccd.sdk;

import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.Test;

public class FPLFunctionalTest {

  @Test
  public void canRunTask() throws IOException {
    // Setup the test build
    File projectDir = new File("../test-builds/fpl-ccd-configuration");

    // Run the build
    GradleRunner runner = FunctionalTest.runner(projectDir);
    runner.build();

    long count = Files.walk(Paths.get("../test-builds/fpl-ccd-configuration/ccd-definition"))
        .count();

    assertThat(count, greaterThan(20L));
  }
}
