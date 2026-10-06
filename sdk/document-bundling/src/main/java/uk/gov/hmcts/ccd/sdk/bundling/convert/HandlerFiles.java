package uk.gov.hmcts.ccd.sdk.bundling.convert;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandlingException;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandlerContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;

final class HandlerFiles {
  private HandlerFiles() {
  }

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
