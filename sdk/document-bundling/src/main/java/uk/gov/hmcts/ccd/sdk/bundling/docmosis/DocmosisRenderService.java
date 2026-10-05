package uk.gov.hmcts.ccd.sdk.bundling.docmosis;

import java.nio.file.Path;
import java.util.Map;

/**
 * The shared Docmosis render service: file-to-PDF conversion ({@code /rs/convert}) and template
 * rendering ({@code /rs/render}).
 *
 * <p>Implementations must bound every call — connection and read timeouts and a source-size
 * ceiling — and must never let the access key
 * appear in logs, errors, or persisted state. Tests and local runs substitute a stub, so the
 * module runs without Docmosis. A stub that only converts need not override the template
 * methods; the renderer checks {@link #rendersTemplates()} before accepting a cover page.
 */
public interface DocmosisRenderService {
  Path convertToPdf(Path source, String fileName, String mediaType) throws DocmosisRenderException;

  default boolean convertsFiles() {
    return true;
  }

  default boolean rendersTemplates() {
    return false;
  }

  default Path renderTemplate(String templateName, Map<String, Object> data)
      throws DocmosisRenderException {
    throw new DocmosisRenderException(
        "This Docmosis render service does not render templates", false);
  }
}
