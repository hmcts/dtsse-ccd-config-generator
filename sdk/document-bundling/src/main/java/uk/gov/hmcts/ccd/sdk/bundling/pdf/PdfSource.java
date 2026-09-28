package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.nio.file.Path;

public record PdfSource(Path path) implements AssemblyContent {
  public PdfSource {
    Checks.requireNonNull(path, "PdfSource.path");
  }
}
