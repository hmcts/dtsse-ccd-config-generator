package uk.gov.hmcts.ccd.sdk.conversion;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Services a {@link PdfConverter} may use while it converts one document.
 */
public interface ConversionContext {

  /**
   * Creates an empty, owner-only file in the conversion's private directory. It is deleted with
   * that directory, so a converter never needs to clean up.
   */
  Path createTempFile(String suffix) throws IOException;
}
