package uk.gov.hmcts.ccd.sdk.bundling.spring;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocuments;

/**
 * Fixtures for the auto-configuration tests: a recording PDF resolver per provider and request
 * builders. Fakes exist only at the consumer ports; wiring and rendering are real.
 */
final class SpringWiringFixtures {

  private SpringWiringFixtures() {
  }

  static byte[] fixture(String name) {
    String resource = "/fixtures/em-stitching/" + name;
    try (InputStream in = SpringWiringFixtures.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing test fixture " + resource);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static BundleDocument pdfDocument(String id, String title, String provider) {
    return BundleDocument.builder()
        .id(id)
        .title(title)
        .date(LocalDate.of(2026, 8, 17))
        .reference(new DocumentReference(provider, id))
        .build();
  }

  static BundleRequest request(BundleDocument... documents) {
    BundleSection.Builder root = BundleSection.builder("Case file");
    for (BundleDocument document : documents) {
      root.document(document);
    }
    return BundleRequest.builder()
        .externalId(UUID.randomUUID())
        .title("Auto-configured bundle")
        .fileName("auto-configured-bundle.pdf")
        .root(root.build())
        .build();
  }

  /** Serves the one-page PDF fixture for every reference, recording each batch. */
  static final class FixturePdfResolver implements DocumentResolver {

    private final String provider;
    private final byte[] pdf = fixture("one-page.pdf");
    final List<List<DocumentReference>> batches = new CopyOnWriteArrayList<>();

    FixturePdfResolver(String provider) {
      this.provider = provider;
    }

    @Override
    public String provider() {
      return provider;
    }

    @Override
    public ResolvedDocuments resolveAll(
        List<DocumentReference> references, BundleExecutionContext context) {
      batches.add(List.copyOf(references));
      Map<DocumentReference, ResolvedDocument> resolved = new LinkedHashMap<>();
      for (DocumentReference reference : references) {
        resolved.put(reference, new PdfResolvedDocument(pdf, reference.id() + ".pdf"));
      }
      return ResolvedDocuments.allResolved(resolved);
    }
  }

  private static final class PdfResolvedDocument implements ResolvedDocument {

    private final byte[] content;
    private final String fileName;

    private PdfResolvedDocument(byte[] content, String fileName) {
      this.content = content;
      this.fileName = fileName;
    }

    @Override
    public InputStream content() {
      return new ByteArrayInputStream(content);
    }

    @Override
    public String mediaType() {
      return "application/pdf";
    }

    @Override
    public String fileName() {
      return fileName;
    }

    @Override
    public OptionalLong contentLength() {
      return OptionalLong.of(content.length);
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
