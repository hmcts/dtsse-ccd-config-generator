package uk.gov.hmcts.ccd.sdk.converter.roundtrip;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * On-demand regeneration of the per-fixture round-trip baselines under
 * {@code src/test/resources/roundtrip-baselines/}. This is <em>not</em> a test — it is
 * {@code @Disabled} so it never runs in a normal build (it would rewrite the very files the ratchet
 * guards against). Run it deliberately after an intended change to a fixture's residuals:
 *
 * <pre>{@code
 *   ./gradlew -p sdk :ccd-definition-converter:roundTripTest \
 *       -Djunit.jupiter.conditions.deactivate='*' \
 *       --tests '*GenerateGoldenFiles'
 * }</pre>
 *
 * <p>It runs every fixture whose submodule is initialised and overwrites that fixture's baseline
 * with the freshly-observed residuals; a fixture whose submodule is absent is skipped and its
 * baseline left untouched. Review the resulting git diff before committing — the baseline changes
 * are the newly-accepted (or newly-closed) gaps.
 */
@Tag("round-trip")
class GenerateGoldenFiles {

  @Test
  @Disabled("Regeneration tool, not a test: run on demand with "
      + "-Djunit.jupiter.conditions.deactivate='*' to rewrite the round-trip baselines.")
  void regenerateBaselines(@TempDir Path work) {
    StringBuilder summary = new StringBuilder("Regenerated round-trip baselines:\n");
    int regenerated = 0;
    for (Fixtures.Fixture fixture : Fixtures.ALL) {
      if (!Files.isDirectory(fixture.input())) {
        summary.append(String.format("  %-8s SKIPPED (submodule not initialised)%n", fixture.name()));
        continue;
      }
      Path fixtureWork = work.resolve(fixture.name());
      List<String> residuals = RoundTripRunner.residuals(fixture, fixtureWork);
      Baselines.write(fixture.name(), residuals);
      summary.append(String.format("  %-8s %d residual line(s) → %s%n",
          fixture.name(), residuals.size(), Baselines.file(fixture.name())));
      regenerated++;
    }
    summary.append("Regenerated ").append(regenerated).append(" baseline(s).");
    System.out.println(summary);
  }
}
