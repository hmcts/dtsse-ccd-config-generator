package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleLimits;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandlerContext;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;

/**
 * The per-document handler context: temp files in the job's owner-only directory, the Docmosis
 * client when configured, the effective limits, and the owning bundle document.
 *
 * <p>Temp-file allocation is capped per document ({@value #MAX_TEMP_FILES_PER_DOCUMENT} files):
 * a runaway or malicious handler cannot exhaust the disk with allocations, and a breach fails
 * that document's conversion typed rather than filling the volume.
 */
final class DefaultHandlerContext implements HandlerContext {

  static final int MAX_TEMP_FILES_PER_DOCUMENT = 100;

  private final Path jobDirectory;
  private final BundleDocument document;
  private final Optional<DocmosisRenderService> docmosisService;
  private final BundleLimits limits;
  private final AtomicInteger allocations = new AtomicInteger();

  DefaultHandlerContext(Path jobDirectory, BundleDocument document,
      Optional<DocmosisRenderService> docmosisService, BundleLimits limits) {
    this.jobDirectory = jobDirectory;
    this.document = document;
    this.docmosisService = docmosisService;
    this.limits = limits;
  }

  @Override
  public BundleDocument document() {
    return document;
  }

  @Override
  public Path createTempFile(String suffix) throws IOException {
    if (allocations.incrementAndGet() > MAX_TEMP_FILES_PER_DOCUMENT) {
      throw new IOException(
          "The handler allocated more than " + MAX_TEMP_FILES_PER_DOCUMENT + " temporary files "
              + "for document '" + document.id() + "'; the per-document allocation cap protects "
              + "the host's disk");
    }
    return JobDirectory.createFile(jobDirectory, suffix);
  }

  @Override
  public Optional<DocmosisRenderService> docmosis() {
    return docmosisService;
  }

  @Override
  public BundleLimits limits() {
    return limits;
  }
}
