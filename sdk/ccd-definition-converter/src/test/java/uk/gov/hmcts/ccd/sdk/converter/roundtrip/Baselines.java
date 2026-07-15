package uk.gov.hmcts.ccd.sdk.converter.roundtrip;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the per-fixture residual baselines under
 * {@code src/test/resources/roundtrip-baselines/<fixture>.txt}, and computes the ratchet verdict
 * that {@link RoundTripTest} asserts.
 *
 * <p>A baseline is the exact, deterministically-normalised set of residual diff lines the fixture
 * is currently expected to produce (see {@link RoundTripRunner}). The file is the enumerated,
 * reviewed list of open gaps for that fixture; a zero-length file means the fixture must round-trip
 * clean. The ratchet: the observed residuals must equal the baseline <em>exactly</em>.</p>
 */
final class Baselines {

  static final Path DIR = Path.of("src/test/resources/roundtrip-baselines");

  private Baselines() {
  }

  static Path file(String fixture) {
    return DIR.resolve(fixture + ".txt");
  }

  /**
   * Reads a fixture's baseline lines. A missing file is treated as an empty (must-round-trip-clean)
   * baseline so a newly-added fixture without a checked-in baseline fails loudly with its residuals
   * rather than erroring on a missing file. Blank trailing lines are ignored.
   */
  static List<String> read(String fixture) {
    Path path = file(fixture);
    if (!Files.isRegularFile(path)) {
      return List.of();
    }
    try {
      List<String> lines = new ArrayList<>();
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        if (!line.isEmpty()) {
          lines.add(line);
        }
      }
      return lines;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read baseline " + path, e);
    }
  }

  /**
   * Overwrites a fixture's baseline with the given residual lines (creating the directory). Used
   * only by {@link GenerateGoldenFiles}.
   */
  static void write(String fixture, List<String> residuals) {
    Path path = file(fixture);
    try {
      Files.createDirectories(path.getParent());
      String content = residuals.isEmpty() ? "" : String.join("\n", residuals) + "\n";
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write baseline " + path, e);
    }
  }

  /**
   * Compares observed residuals against the checked-in baseline and returns a failure message, or
   * {@code null} when they match exactly. The message distinguishes the two ratchet directions:
   *
   * <ul>
   *   <li><b>NEW residuals</b> — a regression: lines present now but absent from the baseline.</li>
   *   <li><b>VANISHED residuals</b> — an improvement: baseline lines the run no longer produces.
   *       This also fails, demanding a baseline refresh, so the ratchet can only tighten.</li>
   * </ul>
   */
  static String diff(String fixture, List<String> observed) {
    List<String> baseline = read(fixture);
    if (observed.equals(baseline)) {
      return null;
    }
    List<String> added = new ArrayList<>(observed);
    added.removeAll(baseline);
    List<String> vanished = new ArrayList<>(baseline);
    vanished.removeAll(observed);

    StringBuilder message = new StringBuilder();
    message.append("Round-trip residuals for fixture '").append(fixture)
        .append("' do not match ").append(file(fixture)).append(".\n")
        .append("  baseline lines: ").append(baseline.size())
        .append(", observed lines: ").append(observed.size()).append('\n');
    if (!added.isEmpty()) {
      message.append("  NEW residuals (").append(added.size())
          .append(") — a regression; these diffs were not in the baseline:\n");
      added.forEach(line -> message.append("    + ").append(line).append('\n'));
    }
    if (!vanished.isEmpty()) {
      message.append("  VANISHED residuals (").append(vanished.size())
          .append(") — an improvement; refresh the baseline "
              + "(./gradlew -p sdk :ccd-definition-converter:roundTripTest "
              + "-Djunit.jupiter.conditions.deactivate='*' "
              + "then run GenerateGoldenFiles) to lock it in:\n");
      vanished.forEach(line -> message.append("    - ").append(line).append('\n'));
    }
    return message.toString();
  }
}
