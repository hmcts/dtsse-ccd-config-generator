package uk.gov.hmcts.ccd.sdk.doctoolkit.pdf;

import java.nio.file.Path;

/** A watermark image and where it goes, as the assembler applies it. */
public record Watermark(Path image, boolean firstPageOnly, boolean behindContent) {
  public Watermark {
    Checks.requireNonNull(image, "Watermark.image");
  }
}
