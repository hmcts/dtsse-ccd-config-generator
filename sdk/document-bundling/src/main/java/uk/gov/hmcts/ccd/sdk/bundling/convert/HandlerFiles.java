package uk.gov.hmcts.ccd.sdk.bundling.convert;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandlingException;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandlerContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;

/**
 * Shared file plumbing for the built-in handlers.
 */
final class HandlerFiles {

  private HandlerFiles() {
  }

  /**
   * Materialises a source as a file: the spooled file directly when the pipeline already wrote
   * one ({@link FileBackedSource}), otherwise a fresh copy of the content stream in the job's
   * temporary directory.
   *
   * @param source the resolved source
   * @param context the handler context that allocates temporary files
   * @param suffix the temporary file suffix used when a copy is needed
   * @return the source content as a file
   * @throws DocumentHandlingException if the content cannot be written to disk
   */
  static Path materialise(ResolvedDocument source, HandlerContext context, String suffix)
      throws DocumentHandlingException {
    if (source instanceof FileBackedSource fileBacked) {
      return fileBacked.file();
    }
    try {
      Path copy = context.createTempFile(suffix);
      try (InputStream in = source.content();
          OutputStream out = Files.newOutputStream(copy)) {
        in.transferTo(out);
      }
      return copy;
    } catch (IOException e) {
      throw new DocumentHandlingException(
          "Could not write the source content to a temporary file", e);
    }
  }
}
