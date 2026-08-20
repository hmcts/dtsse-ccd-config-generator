package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleArtifact;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDestination;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.api.MediaPlaceholder;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailure;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocuments;
import uk.gov.hmcts.ccd.sdk.bundling.api.StoredBundle;

/**
 * Shared fixtures for the rendering pipeline tests: an in-memory resolver (the consumer port),
 * a recording destination wrapper, fixture loading, and request builders. Fakes exist only at
 * the consumer ports; everything between them is the real pipeline.
 */
final class RenderTestSupport {

  static final String PROVIDER = "case-documents";

  private RenderTestSupport() {
  }

  static byte[] fixture(String name) {
    String resource = "/fixtures/em-stitching/" + name;
    try (InputStream in = RenderTestSupport.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing test fixture " + resource);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static BundleDocument doc(String id, String title, String referenceId) {
    return BundleDocument.builder()
        .id(id)
        .title(title)
        .date(LocalDate.of(2026, 3, 14))
        .reference(new DocumentReference(PROVIDER, referenceId))
        .build();
  }

  static BundleDocument mediaDoc(String id, String title, String mediaType) {
    return BundleDocument.builder()
        .id(id)
        .title(title)
        .date(LocalDate.of(2026, 5, 2))
        .reference(new DocumentReference(PROVIDER, id))
        .media(MediaPlaceholder.builder()
            .accessUrl("https://media.example.net/recordings/" + id)
            .mediaType(mediaType)
            .duration(java.time.Duration.ofMinutes(42))
            .note("Playback requires case access")
            .build())
        .build();
  }

  static BundleRequest singleDocumentRequest(BundleDocument document) {
    return BundleRequest.builder()
        .externalId(UUID.randomUUID())
        .title("Test bundle")
        .fileName("test-bundle.pdf")
        .root(BundleSection.builder("Case file").document(document).build())
        .build();
  }

  /** One in-memory source: bytes plus the metadata a resolver declares. */
  record Source(byte[] content, String mediaType, String fileName, OptionalLong declaredLength) {

    static Source of(byte[] content, String mediaType, String fileName) {
      return new Source(content, mediaType, fileName, OptionalLong.of(content.length));
    }
  }

  /** The in-memory consumer resolver: records batches, serves bytes, maps typed failures. */
  static final class InMemoryResolver implements DocumentResolver {

    private final Map<String, Source> sources = new LinkedHashMap<>();
    private final Map<String, ResolutionFailure> failures = new LinkedHashMap<>();
    final List<List<DocumentReference>> batches = new ArrayList<>();

    InMemoryResolver source(String referenceId, Source source) {
      sources.put(referenceId, source);
      return this;
    }

    InMemoryResolver failure(String referenceId, ResolutionFailure failure) {
      failures.put(referenceId, failure);
      return this;
    }

    @Override
    public String provider() {
      return PROVIDER;
    }

    @Override
    public ResolvedDocuments resolveAll(
        List<DocumentReference> references, BundleExecutionContext context) {
      batches.add(List.copyOf(references));
      Map<DocumentReference, ResolvedDocument> resolved = new LinkedHashMap<>();
      Map<DocumentReference, ResolutionFailure> failed = new LinkedHashMap<>();
      for (DocumentReference reference : references) {
        Source source = sources.get(reference.id());
        ResolutionFailure failure = failures.get(reference.id());
        if (failure != null) {
          failed.put(reference, failure);
        } else if (source != null) {
          resolved.put(reference, new InMemoryResolvedDocument(source));
        }
        // A reference with neither entry gets no outcome, exercising the pipeline's guard.
      }
      return new ResolvedDocuments(resolved, failed);
    }
  }

  static final class InMemoryResolvedDocument implements ResolvedDocument {

    private final Source source;
    boolean closed;

    InMemoryResolvedDocument(Source source) {
      this.source = source;
    }

    @Override
    public InputStream content() {
      return new ByteArrayInputStream(source.content());
    }

    @Override
    public String mediaType() {
      return source.mediaType();
    }

    @Override
    public String fileName() {
      return source.fileName();
    }

    @Override
    public OptionalLong contentLength() {
      return source.declaredLength();
    }

    @Override
    public Optional<String> checksum() {
      return Optional.empty();
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  /** Wraps a destination, counting stores, so tests can assert nothing was published. */
  static final class RecordingDestination implements BundleDestination {

    private final BundleDestination delegate;
    final AtomicInteger stores = new AtomicInteger();

    RecordingDestination(BundleDestination delegate) {
      this.delegate = delegate;
    }

    @Override
    public StoredBundle store(BundleArtifact artifact, BundleExecutionContext context) {
      stores.incrementAndGet();
      return delegate.store(artifact, context);
    }
  }
}
