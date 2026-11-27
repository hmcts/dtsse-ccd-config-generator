package uk.gov.hmcts.ccd.sdk.doctoolkit.docmosis;

import java.nio.file.Path;

/**
 * Converts office documents to PDF through the shared Docmosis service ({@code /rs/convert}).
 *
 * <p>Implementations must bound every call (connection and read timeouts and a source-size
 * ceiling) and must never let the access key appear in logs or errors. Tests substitute a stub,
 * so the toolkit runs without Docmosis.
 */
@FunctionalInterface
public interface DocmosisRenderService {

  /**
   * Converts {@code source} to PDF, returning a new file the caller owns.
   *
   * @param fileName the source's name, sent to Docmosis to pick the input format
   * @param mediaType the source's media type
   */
  Path convertToPdf(Path source, String fileName, String mediaType) throws DocmosisRenderException;
}
