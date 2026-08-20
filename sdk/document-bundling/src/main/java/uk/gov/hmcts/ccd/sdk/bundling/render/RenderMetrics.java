package uk.gov.hmcts.ccd.sdk.bundling.render;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.PdfBundleAssembler;

/**
 * The renderer's Micrometer surface, bound to the consumer's registry when one is configured and
 * a no-op otherwise. Published meters:
 *
 * <ul>
 * <li>{@code ccd.bundling.stage} — a timer per pipeline stage, tagged {@code stage};
 * <li>{@code ccd.bundling.documents}, {@code ccd.bundling.pages}, {@code ccd.bundling.bytes} —
 * counters of documents stitched, output pages, and output bytes;
 * <li>{@code ccd.bundling.warnings} — a counter tagged {@code code}. The tag values are bounded:
 * only the module's built-in warning codes are used verbatim; a warning minted by a consumer
 * handler carries the collapsed tag value {@code extension}, so a handler emitting per-document
 * or per-render unique codes cannot explode the registry's tag cardinality;
 * <li>{@code ccd.bundling.failures} — a counter tagged {@code code} with the
 * {@code BundleErrorCode} name (a closed enum, so already bounded).
 * </ul>
 */
final class RenderMetrics {

  /**
   * The built-in warning codes allowed verbatim as {@code ccd.bundling.warnings} tag values.
   */
  private static final Set<String> BUILT_IN_WARNING_CODES = Set.of(
      DefaultBundleRenderer.WARNING_MEDIA_TYPE_MISMATCH,
      DefaultBundleRenderer.WARNING_NO_EXTRACTABLE_TEXT,
      PdfBundleAssembler.WARNING_EMPTY_SECTION_PAGE,
      PdfBundleAssembler.WARNING_STRUCTURE_TREE_REPLACED,
      PdfBundleAssembler.WARNING_OUTLINE_TRUNCATED,
      PdfBundleAssembler.WARNING_TITLE_NOT_RENDERABLE);

  private final MeterRegistry registry;

  RenderMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  void stage(BundleStage stage, Duration elapsed) {
    if (registry != null) {
      registry.timer("ccd.bundling.stage", "stage", stage.name().toLowerCase(Locale.ROOT))
          .record(elapsed);
    }
  }

  void documents(int count) {
    count("ccd.bundling.documents", count);
  }

  void pages(int count) {
    count("ccd.bundling.pages", count);
  }

  void bytes(long count) {
    count("ccd.bundling.bytes", count);
  }

  void warning(String code) {
    if (registry != null) {
      String tag = BUILT_IN_WARNING_CODES.contains(code) ? code : "extension";
      registry.counter("ccd.bundling.warnings", "code", tag).increment();
    }
  }

  void failure(String code) {
    if (registry != null) {
      registry.counter("ccd.bundling.failures", "code", code).increment();
    }
  }

  private void count(String name, long amount) {
    if (registry != null) {
      registry.counter(name).increment(amount);
    }
  }
}
