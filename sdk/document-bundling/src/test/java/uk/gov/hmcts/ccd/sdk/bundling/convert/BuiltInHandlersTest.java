package uk.gov.hmcts.ccd.sdk.bundling.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.bundling.convert.HandlerTestSupport.fixture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleLimits;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandlingException;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandledDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.MediaPlaceholder;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderException;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;

/**
 * The built-in handlers, exercised directly with real files: PDF passthrough verification, the
 * ported image conversion, the generated media link page, and the Docmosis office handler's
 * bounds and mappings.
 */
class BuiltInHandlersTest {

  @TempDir
  Path directory;

  private HandlerTestSupport.TestHandlerContext context() {
    return HandlerTestSupport.TestHandlerContext.in(directory, plainDocument());
  }

  private static BundleDocument plainDocument() {
    return BundleDocument.builder()
        .id("d1")
        .title("A document")
        .reference(new DocumentReference("case-documents", "d1"))
        .build();
  }

  @Nested
  class PdfPassthrough {

    private final PdfPassthroughHandler handler = new PdfPassthroughHandler();

    @Test
    void passesAValidPdfThroughUnchanged() throws Exception {
      Path source = Files.write(directory.resolve("one-page.pdf"), fixture("one-page.pdf"));

      HandledDocument handled = handler.handle(
          new HandlerTestSupport.FileSource(source, "application/pdf", "one-page.pdf"),
          context());

      // Passthrough means the very same spooled file: no copy, no mutation.
      assertThat(handled.pdfFile()).isEqualTo(source);
      assertThat(Files.readAllBytes(source)).isEqualTo(fixture("one-page.pdf"));
    }

    @Test
    void rejectsContentWithoutAPdfSignature() {
      assertThatThrownBy(() -> handler.handle(
          new HandlerTestSupport.StreamSource(
              "not a pdf at all".getBytes(StandardCharsets.UTF_8),
              "application/pdf", "fake.pdf"),
          context()))
          .isInstanceOf(DocumentHandlingException.class)
          .hasMessageContaining("%PDF-");
    }

    @Test
    void rejectsAnEncryptedPdfTyped() throws Exception {
      assertThatThrownBy(() -> handler.handle(
          new HandlerTestSupport.StreamSource(encryptedPdf(), "application/pdf", "locked.pdf"),
          context()))
          .isInstanceOf(DocumentHandlingException.class)
          .hasMessageContaining("encrypted");
    }

    @Test
    void rejectsABrokenPdfTyped() {
      assertThatThrownBy(() -> handler.handle(
          new HandlerTestSupport.StreamSource(
              "%PDF-1.7 but nothing else of substance".getBytes(StandardCharsets.UTF_8),
              "application/pdf", "broken.pdf"),
          context()))
          .isInstanceOf(DocumentHandlingException.class)
          .hasMessageContaining("corrupt");
    }
  }

  @Nested
  class Images {

    private final ImageHandler handler = new ImageHandler();

    @Test
    void rendersAJpegCentredOnASinglePage() throws Exception {
      HandledDocument handled = handler.handle(
          new HandlerTestSupport.StreamSource(fixture("flying-pig.jpg"), "image/jpeg",
              "flying-pig.jpg"),
          context());

      try (PDDocument document = Loader.loadPDF(handled.pdfFile().toFile())) {
        assertThat(document.getNumberOfPages()).isEqualTo(1);
        PDPage page = document.getPage(0);
        assertThat(page.getResources().getXObjectNames()).isNotEmpty();
      }
    }

    @Test
    void scalesDownAnOversizedImagePreservingAspectRatio() throws Exception {
      // 2000x500: wider than a letter page, so it must scale to fit while keeping 4:1.
      BufferedImage image = new BufferedImage(2000, 500, BufferedImage.TYPE_INT_RGB);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);

      HandledDocument handled = handler.handle(
          new HandlerTestSupport.StreamSource(out.toByteArray(), "image/png", "wide.png"),
          context());

      try (PDDocument document = Loader.loadPDF(handled.pdfFile().toFile())) {
        assertThat(document.getNumberOfPages()).isEqualTo(1);
      }
    }

    @Test
    void undecodableContentFailsTyped() {
      assertThatThrownBy(() -> handler.handle(
          new HandlerTestSupport.StreamSource(
              "not an image".getBytes(StandardCharsets.UTF_8), "image/png", "fake.png"),
          context()))
          .isInstanceOf(DocumentHandlingException.class)
          .hasMessageContaining("decoded");
    }
  }

  @Nested
  class MediaLink {

    private final MediaLinkHandler handler = new MediaLinkHandler();

    @Test
    void rendersTheStandardLinkPageFromTheOwningDocumentMetadata() throws Exception {
      BundleDocument media = BundleDocument.builder()
          .id("m1")
          .title("Hearing recording, day 2")
          .date(LocalDate.of(2026, 5, 2))
          .reference(new DocumentReference("case-documents", "m1"))
          .media(MediaPlaceholder.builder()
              .accessUrl("https://media.example.net/recordings/m1")
              .mediaType("audio/mpeg")
              .duration(Duration.ofMinutes(90))
              .note("Playback requires case access")
              .build())
          .build();
      HandlerTestSupport.TestHandlerContext context =
          HandlerTestSupport.TestHandlerContext.in(directory, media);

      HandledDocument handled = handler.handle(
          new HandlerTestSupport.StreamSource(new byte[0], "audio/mpeg", "m1"), context);

      try (PDDocument document = Loader.loadPDF(handled.pdfFile().toFile())) {
        assertThat(document.getNumberOfPages()).isEqualTo(1);
        String text = new PDFTextStripper().getText(document);
        assertThat(text)
            .contains("Hearing recording, day 2")
            .contains("Media type: audio/mpeg")
            .contains("Duration: 1h 30m 0s")
            .contains("Playback requires case access")
            .contains("https://media.example.net/recordings/m1");
      }
    }

    @Test
    void aDocumentWithoutAPlaceholderFailsTyped() {
      assertThatThrownBy(() -> handler.handle(
          new HandlerTestSupport.StreamSource(new byte[0], "audio/mpeg", "m1"), context()))
          .isInstanceOf(DocumentHandlingException.class)
          .hasMessageContaining("media placeholder");
    }
  }

  @Nested
  class DocmosisOffice {

    private final DocmosisOfficeHandler handler = new DocmosisOfficeHandler();

    @Test
    void sendsTheRealFileNameAndMediaTypeAndAdoptsTheConvertedPdf() throws Exception {
      StringBuilder observed = new StringBuilder();
      DocmosisRenderService stub = new DocmosisRenderService() {
        @Override
        public Path convertToPdf(Path source, String fileName, String mediaType)
            throws DocmosisRenderException {
          observed.append(fileName).append('|').append(mediaType);
          try {
            return Files.write(directory.resolve("converted-by-docmosis.pdf"),
                fixture("one-page.pdf"));
          } catch (IOException e) {
            throw new DocmosisRenderException("could not write", false, e);
          }
        }

        @Override
        public Path renderTemplate(String templateName, Map<String, Object> payload) {
          throw new UnsupportedOperationException();
        }
      };

      HandledDocument handled = handler.handle(
          new HandlerTestSupport.StreamSource(fixture("wordDocument.doc"),
              "application/msword", "wordDocument.doc"),
          context().withDocmosis(stub));

      assertThat(observed.toString()).isEqualTo("wordDocument.doc|application/msword");
      // The converted PDF was moved into the job directory so job cleanup owns it.
      assertThat(handled.pdfFile()).hasParent(directory);
      assertThat(directory.resolve("converted-by-docmosis.pdf")).doesNotExist();
      try (PDDocument document = Loader.loadPDF(handled.pdfFile().toFile())) {
        assertThat(document.getNumberOfPages()).isEqualTo(1);
      }
    }

    @Test
    void enforcesTheOfficeSizeCeilingBeforeCallingDocmosis() {
      DocmosisRenderService neverCalled = new DocmosisRenderService() {
        @Override
        public Path convertToPdf(Path source, String fileName, String mediaType) {
          throw new AssertionError("Docmosis must not be called");
        }

        @Override
        public Path renderTemplate(String templateName, Map<String, Object> payload) {
          throw new UnsupportedOperationException();
        }
      };
      BundleLimits tinyOfficeLimit = new BundleLimits(
          100, 300L * 1024 * 1024, 10, 1024L * 1024 * 1024, 1000, Duration.ofMinutes(1));

      assertThatThrownBy(() -> handler.handle(
          new HandlerTestSupport.StreamSource(fixture("wordDocument.doc"),
              "application/msword", "wordDocument.doc"),
          context().withDocmosis(neverCalled).withLimits(tinyOfficeLimit)))
          .isInstanceOf(DocumentHandlingException.class)
          .hasMessageContaining("maxOfficeSourceBytesPerDocument");
    }

    @Test
    void mapsTransientDocmosisFailuresWithTheTransientDetail() {
      DocmosisRenderService failing = new DocmosisRenderService() {
        @Override
        public Path convertToPdf(Path source, String fileName, String mediaType)
            throws DocmosisRenderException {
          throw new DocmosisRenderException("Docmosis returned HTTP 503", true);
        }

        @Override
        public Path renderTemplate(String templateName, Map<String, Object> payload) {
          throw new UnsupportedOperationException();
        }
      };

      assertThatThrownBy(() -> handler.handle(
          new HandlerTestSupport.StreamSource(fixture("wordDocument.doc"),
              "application/msword", "wordDocument.doc"),
          context().withDocmosis(failing)))
          .isInstanceOf(DocumentHandlingException.class)
          .hasMessageContaining("transiently")
          .hasMessageContaining("HTTP 503");
    }

    @Test
    void aPermanentDocmosisFailureCarriesNoTransientMarker() {
      DocmosisRenderService failing = new DocmosisRenderService() {
        @Override
        public Path convertToPdf(Path source, String fileName, String mediaType)
            throws DocmosisRenderException {
          throw new DocmosisRenderException("Docmosis rejected the source", false);
        }

        @Override
        public Path renderTemplate(String templateName, Map<String, Object> payload) {
          throw new UnsupportedOperationException();
        }
      };

      DocumentHandlingException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
          DocumentHandlingException.class,
          () -> handler.handle(
              new HandlerTestSupport.StreamSource(fixture("wordDocument.doc"),
                  "application/msword", "wordDocument.doc"),
              context().withDocmosis(failing)));

      assertThat(failure.getMessage())
          .contains("Docmosis rejected the source")
          .doesNotContain("transiently");
    }
  }

  private static byte[] encryptedPdf() throws IOException {
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      document.protect(new StandardProtectionPolicy("owner", "user", new AccessPermission()));
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }
}
