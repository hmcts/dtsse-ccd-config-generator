package uk.gov.hmcts.ccd.sdk.bundling.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.api.MediaPlaceholder;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailure;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailureReason;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocuments;

final class RenderTestSupport {
  static final String PROVIDER = "case-documents";

  private RenderTestSupport() {
  }

  static byte[] fixture(String name) {
    try (InputStream in = RenderTestSupport.class.getResourceAsStream("/fixtures/em-stitching/" + name)) {
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
    return BundleDocument.builder().id(id).title(title).date(LocalDate.of(2026, 3, 14))
        .reference(new DocumentReference(PROVIDER, referenceId)).build();
  }

  static BundleDocument mediaDoc(String id, String title, String mediaType) {
    return BundleDocument.builder().id(id).title(title).reference(new DocumentReference(PROVIDER, id))
        .media(MediaPlaceholder.builder().accessUrl("https://media.example.net/recordings/" + id)
            .mediaType(mediaType).duration(Duration.ofMinutes(42)).build())
        .build();
  }

  static BundleRequest request(BundleDocument... documents) {
    BundleSection.Builder root = BundleSection.builder("Case file");
    for (BundleDocument document : documents) {
      root.document(document);
    }
    return BundleRequest.builder().externalId(UUID.randomUUID()).title("Test bundle")
        .fileName("test-bundle.pdf").root(root.build()).build();
  }

  static PDDocument loadPdf(BundleResult result) throws IOException {
    try (InputStream in = result.artifact().open()) {
      return Loader.loadPDF(in.readAllBytes());
    }
  }

  static List<String> outlineTitles(PDDocument document) {
    List<String> titles = new ArrayList<>();
    collect(document.getDocumentCatalog().getDocumentOutline(), titles);
    return titles;
  }

  private static void collect(PDOutlineNode node, List<String> into) {
    for (PDOutlineItem child : node.children()) {
      into.add(child.getTitle());
      collect(child, into);
    }
  }

  static void assertNothingLeftBehind(Path tempDir) {
    try (Stream<Path> entries = Files.list(tempDir)) {
      assertThat(entries).as("files left under " + tempDir).isEmpty();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // One in-memory resolved source: bytes plus the metadata a resolver declares; records whether it was read.
  record Source(byte[] bytes, String mediaType, String fileName, OptionalLong contentLength, AtomicBoolean opened)
      implements ResolvedDocument {
    static Source of(byte[] bytes, String mediaType, String fileName) {
      return declaring(bytes, mediaType, fileName, OptionalLong.of(bytes.length));
    }

    static Source declaring(byte[] bytes, String mediaType, String fileName, OptionalLong contentLength) {
      return new Source(bytes, mediaType, fileName, contentLength, new AtomicBoolean());
    }

    @Override
    public InputStream content() {
      opened.set(true);
      return new ByteArrayInputStream(bytes);
    }

    @Override
    public Optional<String> checksum() {
      return Optional.empty();
    }

    @Override
    public void close() {
    }
  }

  // The in-memory consumer resolver: records batches, serves sources, maps typed failures.
  static final class InMemoryResolver implements DocumentResolver {
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private final Map<String, ResolutionFailure> failures = new LinkedHashMap<>();
    final List<List<DocumentReference>> batches = new ArrayList<>();

    InMemoryResolver source(String referenceId, Source source) {
      sources.put(referenceId, source);
      return this;
    }

    InMemoryResolver failure(String referenceId, ResolutionFailureReason reason, String detail) {
      failures.put(referenceId, new ResolutionFailure(reason, detail));
      return this;
    }

    @Override
    public String provider() {
      return PROVIDER;
    }

    @Override
    public ResolvedDocuments resolveAll(List<DocumentReference> references, BundleExecutionContext context) {
      batches.add(List.copyOf(references));
      Map<DocumentReference, ResolvedDocument> resolved = new LinkedHashMap<>();
      Map<DocumentReference, ResolutionFailure> failed = new LinkedHashMap<>();
      for (DocumentReference reference : references) {
        if (failures.containsKey(reference.id())) {
          failed.put(reference, failures.get(reference.id()));
        } else if (sources.containsKey(reference.id())) {
          resolved.put(reference, sources.get(reference.id()));
        }
      }
      return new ResolvedDocuments(resolved, failed);
    }
  }
}
