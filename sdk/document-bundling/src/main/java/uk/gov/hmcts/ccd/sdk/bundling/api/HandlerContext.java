package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;

/**
 * Bounded services available to a {@link DocumentHandler}: temp-file allocation in the job's
 * restricted directory and the Docmosis render service when configured. Deliberately not the
 * assembler, so custom handlers cannot break bundle-wide invariants.
 */
public interface HandlerContext {

  /** The bundle document being handled. */
  BundleDocument document();

  /** Allocates a temporary file in the job-scoped directory; the SDK cleans it up with the job. */
  Path createTempFile(String suffix) throws IOException;

  /** The shared Docmosis render service, when the consuming service has configured it. */
  Optional<DocmosisRenderService> docmosis();
}
