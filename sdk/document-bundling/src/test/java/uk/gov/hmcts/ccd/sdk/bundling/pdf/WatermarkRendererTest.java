package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.doc;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.fixture;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.textPdf;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.tocOnly;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.apache.pdfbox.io.IOUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ports the scenarios of em-stitching's {@code PDFWatermarkTest} semantically: image watermarks
 * overlaid on all pages or the first page only, with two documented divergences pinned — the
 * source file is never overwritten (the service saves the overlay back over its input) and
 * failures propagate instead of being swallowed. Adds the SDK's approved text watermark preset.
 */
class WatermarkRendererTest {

  @TempDir
  Path tmp;

  private Path workDir;
  private Path threePageDoc;
  private Path watermarkImage;

  @BeforeEach
  void setUp() throws IOException {
    workDir = Files.createDirectories(tmp.resolve("work"));
    threePageDoc = textPdf(tmp, "evidence text", 3);
    watermarkImage = fixture("schmcts.png");
  }

  @Test
  void imageWatermarkOnAllPagesOverlaysEveryPage() throws IOException {
    String sourceBefore = TestPdfs.sha256(threePageDoc);
    Watermark watermark = Watermark.image(watermarkImage, Watermark.Scope.ALL_PAGES,
        Watermark.Rendering.OPAQUE);

    Path watermarked = WatermarkRenderer.apply(threePageDoc, watermark, workDir,
        IOUtils.createTempFileOnlyStreamCache(), new PdfFonts());

    assertThat(watermarked).isNotEqualTo(threePageDoc);
    assertThat(watermarked.normalize()).startsWith(workDir);
    assertThat(TestPdfs.sha256(threePageDoc)).isEqualTo(sourceBefore);
    assertThat(Pdfs.pageCount(watermarked)).isEqualTo(3);
    for (int page = 1; page <= 3; page++) {
      assertThat(Pdfs.hasXobject(watermarked, page))
          .as("page %d should carry the overlay image", page)
          .isTrue();
      assertThat(Pdfs.pageText(watermarked, page)).contains("evidence text");
    }
  }

  @Test
  void imageWatermarkOnFirstPageLeavesOtherPagesUntouched() throws IOException {
    Watermark watermark = Watermark.image(watermarkImage, Watermark.Scope.FIRST_PAGE,
        Watermark.Rendering.TRANSLUCENT);

    Path watermarked = WatermarkRenderer.apply(threePageDoc, watermark, workDir,
        IOUtils.createTempFileOnlyStreamCache(), new PdfFonts());

    assertThat(Pdfs.hasXobject(watermarked, 1)).isTrue();
    assertThat(Pdfs.hasXobject(watermarked, 2)).isFalse();
    assertThat(Pdfs.hasXobject(watermarked, 3)).isFalse();
  }

  @Test
  void textWatermarkDrawsTheTextOnEveryPage() throws IOException {
    Watermark watermark = Watermark.text("OFFICIAL COPY", Watermark.Scope.ALL_PAGES);

    Path watermarked = WatermarkRenderer.apply(threePageDoc, watermark, workDir,
        IOUtils.createTempFileOnlyStreamCache(), new PdfFonts());

    for (int page = 1; page <= 3; page++) {
      assertThat(Pdfs.pageText(watermarked, page))
          .contains("OFFICIAL COPY")
          .contains("evidence text");
    }
  }

  @Test
  void missingWatermarkImageFailsInsteadOfBeingSwallowed() {
    Watermark watermark = Watermark.image(tmp.resolve("no-such-image.png"),
        Watermark.Scope.ALL_PAGES, Watermark.Rendering.OPAQUE);

    assertThatThrownBy(() -> WatermarkRenderer.apply(threePageDoc, watermark, workDir,
        IOUtils.createTempFileOnlyStreamCache(), new PdfFonts()))
        .isInstanceOf(IOException.class);
  }

  @Test
  void assemblerAppliesTheWatermarkToSourcePagesButNotGeneratedPages() throws IOException {
    String sourceBefore = TestPdfs.sha256(threePageDoc);
    AssemblyRequest request = new AssemblyRequest("Title of the bundle", "stitched.pdf",
        Optional.empty(), tocOnly(), false,
        Optional.of(Watermark.text("OFFICIAL COPY", Watermark.Scope.ALL_PAGES)),
        TestPdfs.nodes(doc("Bundle Doc 1", threePageDoc)));

    AssemblyResult result = new PdfBundleAssembler().assemble(request, workDir);

    assertThat(TestPdfs.sha256(threePageDoc)).isEqualTo(sourceBefore);
    assertThat(Pdfs.pageText(result.outputPdf(), 1)).doesNotContain("OFFICIAL COPY");
    for (int page = 2; page <= 4; page++) {
      assertThat(Pdfs.pageText(result.outputPdf(), page)).contains("OFFICIAL COPY");
    }
  }
}
