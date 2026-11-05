package uk.gov.hmcts.ccd.sdk.conversion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A converted PDF on local disk, in a private directory the converter created for it.
 *
 * <p>Closing it deletes the file and its directory, so read or copy the file before closing.
 * Use it in a try-with-resources block.
 */
public final class ConvertedPdf implements AutoCloseable {

  private final Path file;
  private final Path workDirectory;
  private final int pageCount;
  private final String mediaType;
  private final List<ConversionWarning> warnings;

  ConvertedPdf(Path file, Path workDirectory, int pageCount, String mediaType,
      List<ConversionWarning> warnings) {
    this.file = file;
    this.workDirectory = workDirectory;
    this.pageCount = pageCount;
    this.mediaType = mediaType;
    this.warnings = List.copyOf(warnings);
  }

  /**
   * The PDF file. Valid until {@link #close()}.
   */
  public Path file() {
    return file;
  }

  /** Opens the PDF for reading. */
  public InputStream open() throws IOException {
    return Files.newInputStream(file);
  }

  /** The number of pages in the PDF. */
  public int pageCount() {
    return pageCount;
  }

  /** The media type the source was converted as, after checking the declared type. */
  public String mediaType() {
    return mediaType;
  }

  /** Warnings about the source. Empty when nothing was unusual. */
  public List<ConversionWarning> warnings() {
    return warnings;
  }

  /** Deletes the PDF and its directory. */
  @Override
  public void close() {
    WorkDirectory.deleteRecursively(workDirectory);
  }
}
