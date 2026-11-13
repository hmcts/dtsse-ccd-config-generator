package uk.gov.hmcts.ccd.sdk.conversion;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * Converts documents of one or more media types to PDF. Register one with
 * {@link DocumentConverter.Builder#converter} (or, in Spring, declare it as a bean) to support a
 * new type, or to change how a built-in type is converted. PDF, images and the Docmosis office
 * formats are converted by built-in converters registered the same way.
 *
 * <p>{@link DocumentConverter} does the work every type shares, so a converter only has to turn
 * the source into a PDF:
 * <ul>
 *   <li>before calling it, the declared media type is checked against the content, and
 *       {@code source.mediaType()} is the type the source was routed as, one of
 *       {@link #mediaTypes()};</li>
 *   <li>after it returns, {@code output} must be a readable, unencrypted PDF with at least one
 *       page, or the conversion fails;</li>
 *   <li>for print-ready output the PDF is then rendered to the print standard, so a converter
 *       never deals with margins, colour or resolution.</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe. Throw {@link ConversionException} for a document that
 * cannot be converted, saying whether a retry could succeed. An {@link IOException} is treated as
 * a temporary failure; any other exception as a problem with the document, reported without its
 * message.
 */
public interface PdfConverter {

  /**
   * The media types this converter handles, for example {@code application/vnd.ms-outlook}. They
   * are compared lower case and without parameters.
   */
  Set<String> mediaTypes();

  /**
   * Writes {@code source} to {@code output} as PDF.
   *
   * @param source the document, with the media type it was routed as; never modify or delete it
   * @param output an existing empty file to write the PDF to
   * @param context scratch space that is deleted with the conversion
   */
  void convert(ConversionSource source, Path output, ConversionContext context)
      throws ConversionException, IOException;
}
