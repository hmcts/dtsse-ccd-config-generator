package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.nio.file.Path;
import uk.gov.hmcts.ccd.sdk.bundling.api.WatermarkPreset;

public record Watermark(Path image, WatermarkPreset.Scope scope,
    WatermarkPreset.Rendering rendering) {
  public Watermark {
    Checks.requireNonNull(image, "Watermark.image");
    Checks.requireNonNull(scope, "Watermark.scope");
    Checks.requireNonNull(rendering, "Watermark.rendering");
  }
}
