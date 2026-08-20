package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.nio.file.Path;

/**
 * An already-converted source PDF on disk. The assembler reads the file and never modifies it;
 * any transformation (for example watermarking) happens on a copy under the job's working
 * directory.
 *
 * @param path the path of the source PDF
 */
public record PdfSource(Path path) implements AssemblyContent {

  /**
   * Validates the source path.
   *
   * @param path the path of the source PDF
   */
  public PdfSource {
    Checks.requireNonNull(path, "PdfSource.path");
  }
}
