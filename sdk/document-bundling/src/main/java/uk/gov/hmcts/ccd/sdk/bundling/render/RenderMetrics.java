package uk.gov.hmcts.ccd.sdk.bundling.render;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.PdfBundleAssembler;

final class RenderMetrics {
  private static final Set<String> BUILT_IN_WARNING_CODES = Set.of(
      PdfBundleAssembler.WARNING_EMPTY_SECTION_PAGE,
      PdfBundleAssembler.WARNING_STRUCTURE_TREE_REPLACED,
      PdfBundleAssembler.WARNING_TITLE_NOT_RENDERABLE,
      PdfBundleAssembler.WARNING_OUTLINE_TRUNCATED,
      DefaultBundleRenderer.WARNING_MEDIA_TYPE_MISMATCH,
      DefaultBundleRenderer.WARNING_NO_EXTRACTABLE_TEXT);

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

  void rendered(int documents, int pages, long bytes) {
    if (registry != null) {
      registry.counter("ccd.bundling.documents").increment(documents);
      registry.counter("ccd.bundling.pages").increment(pages);
      registry.counter("ccd.bundling.bytes").increment(bytes);
    }
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
}
