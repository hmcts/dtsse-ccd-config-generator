package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandlerContext;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;

final class DefaultHandlerContext implements HandlerContext {
  static final int MAX_TEMP_FILES_PER_DOCUMENT = 100;

  private final Path jobDirectory;
  private final BundleDocument document;
  private final Optional<DocmosisRenderService> docmosisService;
  private final AtomicInteger allocations = new AtomicInteger();

  DefaultHandlerContext(Path jobDirectory, BundleDocument document,
      Optional<DocmosisRenderService> docmosisService) {
    this.jobDirectory = jobDirectory;
    this.document = document;
    this.docmosisService = docmosisService;
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
}
