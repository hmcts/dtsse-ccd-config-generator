package uk.gov.hmcts.ccd.sdk.conversion.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Golden files for print-ready output, under {@code src/test/resources/golden/<scenario>/}:
 *
 * <ul>
 *   <li>{@code facts.json}: {@link PrintFacts}, compared exactly;</li>
 *   <li>{@code page-N.png}: each page rendered at {@link #PREVIEW_DPI} in grey, compared
 *       within {@link #MAX_MEAN_DIFFERENCE} and {@link #MAX_CHANGED_FRACTION}, which absorbs
 *       platform anti-aliasing but not a moved, missing, rotated or recoloured element.</li>
 * </ul>
 *
 * <p>Regenerate with {@code ./gradlew -p sdk :document-conversion:test -PupdateGoldens}, then
 * review the PNG diffs before committing.
 */
public final class Goldens {
  public static final float PREVIEW_DPI = 30;
  public static final double MAX_MEAN_DIFFERENCE = 2.5;
  public static final double MAX_CHANGED_FRACTION = 0.01;
  private static final int CHANGED_THRESHOLD = 64;

  private static final ObjectMapper JSON =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private Goldens() {
  }

  /** Whether this run rewrites the goldens instead of checking them. */
  public static boolean updating() {
    return Boolean.getBoolean("conversion.updateGoldens");
  }

  /** Checks (or, when updating, rewrites) a scenario's goldens. */
  public static void verify(String scenario, Path pdf) throws IOException {
    Path dir = directory(scenario);
    String facts = JSON.writeValueAsString(PrintFacts.extract(pdf)) + "\n";
    List<BufferedImage> pages = PrintFacts.render(pdf, PREVIEW_DPI);
    if (updating()) {
      Files.createDirectories(dir);
      try (Stream<Path> old = Files.list(dir)) {
        for (Path file : old.toList()) {
          Files.delete(file);
        }
      }
      Files.writeString(dir.resolve("facts.json"), facts, StandardCharsets.UTF_8);
      for (int i = 0; i < pages.size(); i++) {
        ImageIO.write(pages.get(i), "png", dir.resolve("page-" + (i + 1) + ".png").toFile());
      }
      return;
    }
    assertThat(dir.resolve("facts.json"))
        .as("golden for '%s' is missing; run with -PupdateGoldens to create it", scenario)
        .exists();
    assertThat(facts).as("facts for '%s'", scenario)
        .isEqualTo(Files.readString(dir.resolve("facts.json"), StandardCharsets.UTF_8));
    for (int i = 0; i < pages.size(); i++) {
      BufferedImage golden = ImageIO.read(dir.resolve("page-" + (i + 1) + ".png").toFile());
      Difference difference = compare(golden, pages.get(i));
      assertThat(difference.acceptable())
          .as("page %d of '%s' looks different from its golden: %s", i + 1, scenario,
              difference)
          .isTrue();
    }
  }

  /** The facts golden of a scenario, parsed. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> facts(String scenario) throws IOException {
    return JSON.readValue(directory(scenario).resolve("facts.json").toFile(), Map.class);
  }

  /** A golden page preview. */
  public static BufferedImage page(String scenario, int page) throws IOException {
    return ImageIO.read(directory(scenario).resolve("page-" + page + ".png").toFile());
  }

  /** How far two greyscale renders differ. */
  public record Difference(boolean sameSize, double meanDifference, double changedFraction) {
    public boolean acceptable() {
      return sameSize && meanDifference <= MAX_MEAN_DIFFERENCE
          && changedFraction <= MAX_CHANGED_FRACTION;
    }

    @Override
    public String toString() {
      return sameSize
          ? String.format(Locale.ROOT, "mean difference %.2f (max %.2f), %.2f%% of pixels "
              + "changed (max %.2f%%)", meanDifference, MAX_MEAN_DIFFERENCE,
              changedFraction * 100, MAX_CHANGED_FRACTION * 100)
          : "the page sizes differ";
    }
  }

  /** Compares two greyscale renders pixel by pixel. */
  public static Difference compare(BufferedImage expected, BufferedImage actual) {
    if (expected.getWidth() != actual.getWidth() || expected.getHeight() != actual.getHeight()) {
      return new Difference(false, Double.MAX_VALUE, 1);
    }
    long total = 0;
    long changed = 0;
    for (int y = 0; y < expected.getHeight(); y++) {
      for (int x = 0; x < expected.getWidth(); x++) {
        int delta = Math.abs((expected.getRGB(x, y) & 0xFF) - (actual.getRGB(x, y) & 0xFF));
        total += delta;
        if (delta > CHANGED_THRESHOLD) {
          changed++;
        }
      }
    }
    double pixels = (double) expected.getWidth() * expected.getHeight();
    return new Difference(true, total / pixels, changed / pixels);
  }

  private static Path directory(String scenario) {
    return Path.of(System.getProperty("conversion.goldenDir")).resolve(scenario);
  }
}
