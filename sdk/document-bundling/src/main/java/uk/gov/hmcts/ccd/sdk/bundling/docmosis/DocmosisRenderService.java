package uk.gov.hmcts.ccd.sdk.bundling.docmosis;

import java.nio.file.Path;
import java.util.Map;

/**
 * The shared Docmosis render service: file-to-PDF conversion ({@code /rs/convert}) and template
 * rendering ({@code /rs/render}).
 *
 * <p>Implementations must bound every call — connection and read timeouts, a source-size
 * ceiling, and bounded retry on transient failures only — and must never let the access key
 * appear in logs, errors, or persisted state. Tests and local runs substitute a stub, so the
 * module runs without Docmosis.
 */
public interface DocmosisRenderService {

  /**
   * Converts a source file to PDF.
   *
   * @param source the source file to convert
   * @param fileName the source file name, carrying the extension Docmosis uses to pick a
   *     converter
   * @param mediaType the source media type
   * @return the converted PDF file
   * @throws DocmosisRenderException if conversion fails
   */
  Path convertToPdf(Path source, String fileName, String mediaType) throws DocmosisRenderException;

  /**
   * Renders a Docmosis template to PDF, used for consumer cover-page templates during migration.
   *
   * @param templateName the template name, supplied by the consuming service
   * @param payload the template data
   * @return the rendered PDF file
   * @throws DocmosisRenderException if rendering fails
   */
  Path renderTemplate(String templateName, Map<String, Object> payload)
      throws DocmosisRenderException;
}
