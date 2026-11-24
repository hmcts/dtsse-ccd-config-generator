package uk.gov.hmcts.ccd.sdk.doctoolkit.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.Pdfs.doc;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.Pdfs.fixture;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.Pdfs.textPdf;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.Pdfs.tocOnly;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.pdfbox.io.IOUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatermarkRendererTest {
  @TempDir
  Path tmp;

  private Path workDir;
  private Path threePageDoc;
  private Path image;

  @BeforeEach
  void setUp() throws IOException {
    workDir = Files.createDirectories(tmp.resolve("work"));
    threePageDoc = textPdf(tmp, "evidence text", 3);
    image = fixture("schmcts.png");
  }

  private static Watermark image(Path image, boolean firstPageOnly) {
    return new Watermark(image, firstPageOnly, false);
  }

  @Test
  void allPagesOverlaysEveryPageWithoutTouchingTheSourceOrItsText() throws IOException {
    byte[] sourceBefore = Files.readAllBytes(threePageDoc);

    Path watermarked = WatermarkRenderer.apply(threePageDoc,
        image(image, false), workDir, IOUtils.createTempFileOnlyStreamCache());

    assertThat(watermarked).isNotEqualTo(threePageDoc);
    assertThat(watermarked.normalize()).startsWith(workDir);
    assertThat(Files.readAllBytes(threePageDoc)).isEqualTo(sourceBefore);
    for (int page = 1; page <= 3; page++) {
      assertThat(Pdfs.imageCount(watermarked, page)).as("page %d carries the overlay", page).isEqualTo(1);
      assertThat(Pdfs.pageText(watermarked, page)).contains("evidence text");
    }
  }

  @Test
  void firstPageScopeLeavesTheOtherPagesUntouchedInEitherLayer() throws IOException {
    for (boolean behind : new boolean[] {false, true}) {
      Path watermarked = WatermarkRenderer.apply(threePageDoc,
          new Watermark(image, true, behind), workDir,
          IOUtils.createTempFileOnlyStreamCache());

      assertThat(Stream.of(1, 2, 3).map(page -> Pdfs.imageCount(watermarked, page)).toList())
          .as("behind content: %s", behind).containsExactly(1L, 0L, 0L);
    }
  }

  @Test
  void missingImageFailsInsteadOfBeingSwallowedAndLeavesNoOutput() {
    assertThatThrownBy(() -> WatermarkRenderer.apply(threePageDoc,
        image(tmp.resolve("no-such-image.png"), false), workDir,
        IOUtils.createTempFileOnlyStreamCache()))
        .isInstanceOf(IOException.class);
    assertThat(workDir).isEmptyDirectory();
  }

  @Test
  void assemblerWatermarksSourcePagesOnlyAndRemovesItsIntermediateFiles() throws IOException {
    byte[] sourceBefore = Files.readAllBytes(threePageDoc);
    AssemblyRequest request = new AssemblyRequest("Title of the bundle", "stitched.pdf",
        Optional.empty(), tocOnly().withSectionCoverSheets(true), false, Optional.empty(),
        Optional.of(image(image, false)),
        List.of(Pdfs.folder("Folder", doc("Bundle Doc 1", threePageDoc))));

    AssemblyResult result = new PdfBundleAssembler().assemble(request, workDir);

    assertThat(Files.readAllBytes(threePageDoc)).isEqualTo(sourceBefore);
    assertThat(result.totalPages()).isEqualTo(5);
    // Index and folder cover sheet are generated pages: never watermarked.
    assertThat(Stream.of(1, 2, 3, 4, 5).map(page -> Pdfs.imageCount(result.outputPdf(), page)).toList())
        .containsExactly(0L, 0L, 1L, 1L, 1L);
    assertThat(Pdfs.pageText(result.outputPdf(), 3)).contains("evidence text");
    try (Stream<Path> files = Files.list(workDir)) {
      assertThat(files).containsExactly(result.outputPdf());
    }
  }
}
