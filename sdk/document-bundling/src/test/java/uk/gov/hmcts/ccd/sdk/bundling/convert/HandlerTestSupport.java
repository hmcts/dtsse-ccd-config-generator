package uk.gov.hmcts.ccd.sdk.bundling.convert;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleLimits;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandlerContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;

/**
 * Shared fixtures for handler tests: a test handler context and in-memory sources.
 */
final class HandlerTestSupport {

  private HandlerTestSupport() {
  }

  static byte[] fixture(String name) {
    String resource = "/fixtures/em-stitching/" + name;
    try (InputStream in = HandlerTestSupport.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing test fixture " + resource);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  record TestHandlerContext(
      Path directory,
      BundleDocument bundleDocument,
      Optional<DocmosisRenderService> docmosisService,
      BundleLimits limits) implements HandlerContext {

    static TestHandlerContext in(Path directory, BundleDocument document) {
      return new TestHandlerContext(
          directory, document, Optional.empty(), BundleLimits.defaults());
    }

    TestHandlerContext withDocmosis(DocmosisRenderService docmosis) {
      return new TestHandlerContext(directory, bundleDocument, Optional.of(docmosis), limits);
    }

    TestHandlerContext withLimits(BundleLimits newLimits) {
      return new TestHandlerContext(directory, bundleDocument, docmosisService, newLimits);
    }

    @Override
    public BundleDocument document() {
      return bundleDocument;
    }

    @Override
    public Path createTempFile(String suffix) throws IOException {
      return Files.createTempFile(directory, "handler-", suffix);
    }

    @Override
    public Optional<DocmosisRenderService> docmosis() {
      return docmosisService;
    }
  }

  /** A stream-backed source, exercising the copy path a non-file-backed source takes. */
  record StreamSource(byte[] bytes, String mediaType, String fileName)
      implements ResolvedDocument {

    @Override
    public InputStream content() {
      return new ByteArrayInputStream(bytes);
    }

    @Override
    public OptionalLong contentLength() {
      return OptionalLong.of(bytes.length);
    }

    @Override
    public Optional<String> checksum() {
      return Optional.empty();
    }

    @Override
    public void close() {
    }
  }

  /** A file-backed source, exercising the pipeline's spooled path. */
  record FileSource(Path file, String mediaType, String fileName) implements FileBackedSource {

    @Override
    public InputStream content() {
      try {
        return Files.newInputStream(file);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public OptionalLong contentLength() {
      try {
        return OptionalLong.of(Files.size(file));
      } catch (IOException e) {
        return OptionalLong.empty();
      }
    }

    @Override
    public Optional<String> checksum() {
      return Optional.empty();
    }

    @Override
    public void close() {
    }
  }
}
