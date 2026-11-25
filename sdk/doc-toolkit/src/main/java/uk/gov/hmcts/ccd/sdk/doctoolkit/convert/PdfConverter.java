package uk.gov.hmcts.ccd.sdk.doctoolkit.convert;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * Converts sources of some media types to PDF. The built-in ones are in {@link BuiltInConverters}.
 */
interface PdfConverter {

  /** The media types this converter handles. */
  Set<String> mediaTypes();

  /**
   * Writes {@code source} to {@code output} as PDF.
   */
  void convert(ConversionSource source, Path output) throws ConversionException, IOException;
}
