package uk.gov.hmcts.ccd.sdk.converter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;

class ConvertCommandTest {

  @TempDir
  Path tempDir;

  private ConvertCommand parse(String... args) {
    ConvertCommand command = new ConvertCommand();
    new CommandLine(command).parseArgs(args);
    return command;
  }

  private String[] validArgs() {
    return new String[] {
        "--input", tempDir.toString(),
        "--output-src", tempDir.resolve("out/src").toString(),
        "--model-package", "uk.gov.hmcts.test.model",
        "--config-package", "uk.gov.hmcts.test.config",
    };
  }

  @Test
  void buildsOptionsWithDefaults() {
    ConversionOptions options = parse(validArgs()).buildOptions();

    assertThat(options.getEventsPerConfig()).isEqualTo(40);
    assertThat(options.getOverlaySuffixes()).containsKeys("prod", "nonprod");
    assertThat(options.getOverlaySuffixes().get("prod").isNegated()).isFalse();
    assertThat(options.getOverlaySuffixes().get("nonprod").isNegated()).isTrue();
    assertThat(options.getPassthroughDir())
        .isEqualTo(tempDir.resolve("out/resources/ccd-passthrough"));
  }

  @Test
  void derivesRootPackageFromModelPackageWhenNotGiven() {
    // The root config package is derived from --model-package: the model package is cut at its first
    // 'model' segment and '.ccd' appended, keeping the config a sibling of the model.
    assertThat(ConvertCommand.deriveRootPackage("uk.gov.hmcts.probate.model.ccd.raw"))
        .isEqualTo("uk.gov.hmcts.probate.ccd");
    assertThat(ConvertCommand.deriveRootPackage("uk.gov.hmcts.divorce.divorcecase.model"))
        .isEqualTo("uk.gov.hmcts.divorce.divorcecase.ccd");
    // No 'model' segment: '.ccd' is appended verbatim.
    assertThat(ConvertCommand.deriveRootPackage("uk.gov.hmcts.sscs.domain"))
        .isEqualTo("uk.gov.hmcts.sscs.domain.ccd");
  }

  @Test
  void derivedRootPackageDrivesConfigPackageWhenConfigOmitted() {
    ConvertCommand command = parse(
        "--input", tempDir.toString(),
        "--output-src", tempDir.resolve("out").toString(),
        "--model-package", "uk.gov.hmcts.probate.model.ccd.raw");
    assertThat(command.buildOptions().getConfigPackage()).isEqualTo("uk.gov.hmcts.probate.ccd");
  }

  @Test
  void rootPackageAliasOverridesDerivation() {
    // --root-package is an alias for --config-package and overrides the derived value.
    ConvertCommand command = parse(
        "--input", tempDir.toString(),
        "--output-src", tempDir.resolve("out").toString(),
        "--model-package", "uk.gov.hmcts.probate.model.ccd.raw",
        "--root-package", "uk.gov.hmcts.probate.definitions");
    assertThat(command.buildOptions().getConfigPackage())
        .isEqualTo("uk.gov.hmcts.probate.definitions");
  }

  @Test
  void parsesCustomOverlaySuffixes() {
    ConvertCommand command = parse(concat(validArgs(),
        "--overlay-suffix", "WA=WA_ENABLED:true",
        "--overlay-suffix", "nonWA=!WA_ENABLED:true"));

    ConversionOptions options = command.buildOptions();

    assertThat(options.getOverlaySuffixes().get("WA").getEnvVar()).isEqualTo("WA_ENABLED");
    assertThat(options.getOverlaySuffixes().get("nonWA").isNegated()).isTrue();
  }

  @Test
  void rejectsPackagesOutsideUkGovHmcts() {
    ConvertCommand command = parse(
        "--input", tempDir.toString(),
        "--output-src", tempDir.resolve("out").toString(),
        "--model-package", "com.example.model",
        "--config-package", "uk.gov.hmcts.test.config");

    assertThatThrownBy(command::buildOptions)
        .isInstanceOf(CommandLine.ParameterException.class)
        .hasMessageContaining("uk.gov.hmcts");
  }

  @Test
  void rejectsPackagePrefixTricks() {
    ConvertCommand command = parse(
        "--input", tempDir.toString(),
        "--output-src", tempDir.resolve("out").toString(),
        "--model-package", "uk.gov.hmctsish.model",
        "--config-package", "uk.gov.hmcts.test.config");

    assertThatThrownBy(command::buildOptions)
        .isInstanceOf(CommandLine.ParameterException.class);
  }

  @Test
  void rejectsMissingInputDirectory() {
    ConvertCommand command = parse(
        "--input", tempDir.resolve("does-not-exist").toString(),
        "--output-src", tempDir.resolve("out").toString(),
        "--model-package", "uk.gov.hmcts.test.model",
        "--config-package", "uk.gov.hmcts.test.config");

    assertThatThrownBy(command::buildOptions)
        .isInstanceOf(CommandLine.ParameterException.class)
        .hasMessageContaining("does not exist");
  }

  @Test
  void parsesTypePackageHintsIntoRetrofitOptions() throws Exception {
    // D1: repeatable --type-package-hint TypeName=fully.qualified.package is captured for the resolver.
    Path modelRoot = tempDir.resolve("model");
    java.nio.file.Files.createDirectories(modelRoot);
    ConvertCommand command = parse(
        "--input", tempDir.toString(),
        "--output-src", tempDir.resolve("out/src").toString(),
        "--model-package", "uk.gov.hmcts.test.model",
        "--config-package", "uk.gov.hmcts.test.config",
        "--retrofit",
        "--model-source-root", modelRoot.toString(),
        "--type-package-hint", "HearingLength=uk.gov.hmcts.test.model.dq",
        "--type-package-hint", "CaseLocationCivil=uk.gov.hmcts.test.model.genapplication");

    ConversionOptions options = command.buildRetrofitOptions();
    assertThat(options.getRetrofitTypePackageHints())
        .containsEntry("HearingLength", "uk.gov.hmcts.test.model.dq")
        .containsEntry("CaseLocationCivil", "uk.gov.hmcts.test.model.genapplication");
  }

  @Test
  void rejectsMalformedOverlaySpec() {
    ConvertCommand command = parse(concat(validArgs(), "--overlay-suffix", "prod=nocolon"));

    assertThatThrownBy(command::buildOptions)
        .isInstanceOf(CommandLine.ParameterException.class)
        .hasMessageContaining("ENV_VAR:value");
  }

  @Test
  void executeReportsUsageErrorsOnStderr() {
    StringWriter err = new StringWriter();
    CommandLine commandLine = new CommandLine(new ConvertCommand());
    commandLine.setErr(new PrintWriter(err));

    int exitCode = commandLine.execute("--input", tempDir.toString());

    assertThat(exitCode).isEqualTo(2);
    assertThat(err.toString()).contains("--output-src");
  }

  private static String[] concat(String[] base, String... extra) {
    String[] all = new String[base.length + extra.length];
    System.arraycopy(base, 0, all, 0, base.length);
    System.arraycopy(extra, 0, all, base.length, extra.length);
    return all;
  }
}
