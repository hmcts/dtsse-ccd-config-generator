package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;

/**
 * Bounded services available to a {@link DocumentHandler}: temp-file allocation in the job's
 * restricted directory, the Docmosis render service when configured, and the effective limits.
 * Deliberately not the assembler, so custom handlers cannot break bundle-wide invariants.
 */
public interface HandlerContext {

  /**
   * The bundle document being handled: the request-side metadata that owns the source passed to
   * {@link DocumentHandler#handle}.
   *
   * <p>This accessor exists chiefly for media documents, whose content is never fetched by
   * design: the pipeline synthesises a metadata-only {@link ResolvedDocument} for them (no
   * content; the media type declared in the request), so the handler builds its page from this
   * document's {@link BundleDocument#title() title}, {@link BundleDocument#date() date}, and
   * {@link BundleDocument#media() media placeholder} instead. Consumer replacements for the
   * built-in media link handler (a branded media page, for example) use the same seam, keeping
   * one handler SPI for fetched and metadata-only documents alike. Handlers for ordinary fetched
   * documents may also read it for display metadata; it never grants access to other documents
   * or to the assembler.
   *
   * @return the bundle document this handler invocation is producing pages for
   */
  BundleDocument document();

  /**
   * Allocates a temporary file in the job-scoped, owner-only directory. The SDK cleans it up
   * with the job, including on cancellation or failure.
   *
   * @param suffix the file name suffix, for example {@code .pdf}
   * @return the path of the new empty file
   * @throws IOException if the file cannot be created
   */
  Path createTempFile(String suffix) throws IOException;

  /**
   * The shared Docmosis render service, when the consuming service has configured it.
   *
   * @return the Docmosis render service, or empty when not configured
   */
  Optional<DocmosisRenderService> docmosis();

  /**
   * The effective limits for this render, which handlers must respect.
   *
   * @return the limits
   */
  BundleLimits limits();
}
