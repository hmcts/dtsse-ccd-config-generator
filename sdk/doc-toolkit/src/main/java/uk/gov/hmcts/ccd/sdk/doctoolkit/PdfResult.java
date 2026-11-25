package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A rendered PDF in a temporary directory. Close it to delete the file.
 *
 * <pre>{@code
 * try (PdfResult result = toolkit.render(request)) {
 *   store(result.open());
 * }
 * }</pre>
 */
public final class PdfResult implements AutoCloseable {
  private final Path file;
  private final String fileName;
  private final int pageCount;
  private final List<DocFailure> failures;
  private final Runnable cleanup;

  /** Created by the toolkit. */
  public PdfResult(Path file, String fileName, int pageCount, List<DocFailure> failures,
      Runnable cleanup) {
    this.file = Validate.requireNonNull(file, "file");
    this.fileName = Validate.requireNonBlank(fileName, "fileName");
    this.pageCount = pageCount;
    this.failures = List.copyOf(failures);
    this.cleanup = Validate.requireNonNull(cleanup, "cleanup");
  }

  /** The PDF. */
  public Path file() {
    return file;
  }

  /** Opens the PDF for reading. */
  public InputStream open() throws IOException {
    return Files.newInputStream(file);
  }

  /** The file name the request asked for. */
  public String fileName() {
    return fileName;
  }

  /** The number of pages. */
  public int pageCount() {
    return pageCount;
  }

  /** The documents that could not be included; each has a placeholder page instead. */
  public List<DocFailure> failures() {
    return failures;
  }

  /** Deletes the PDF and its temporary directory. */
  @Override
  public void close() {
    cleanup.run();
  }
}
