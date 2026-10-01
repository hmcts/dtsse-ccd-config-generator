package uk.gov.hmcts.ccd.sdk.bundling.docmosis;

import java.nio.file.Path;

/**
 * The shared Docmosis render service: file-to-PDF conversion ({@code /rs/convert}).
 *
 * <p>Implementations must bound every call — connection and read timeouts and a source-size
 * ceiling — and must never let the access key
 * appear in logs, errors, or persisted state. Tests and local runs substitute a stub, so the
 * module runs without Docmosis.
 */
public interface DocmosisRenderService {
  Path convertToPdf(Path source, String fileName, String mediaType) throws DocmosisRenderException;

}
