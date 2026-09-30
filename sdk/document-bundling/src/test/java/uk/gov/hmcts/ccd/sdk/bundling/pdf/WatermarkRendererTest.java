package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.Pdfs.doc;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.Pdfs.fixture;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.Pdfs.textPdf;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.Pdfs.tocOnly;

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
import uk.gov.hmcts.ccd.sdk.bundling.api.WatermarkPreset;

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

  private static Watermark image(Path image, WatermarkPreset.Scope scope) {
    return new Watermark(image, scope, WatermarkPreset.Rendering.OPAQUE);
  }

  @Test
  void allPagesOverlaysEveryPageWithoutTouchingTheSourceOrItsText() throws IOException {
    byte[] sourceBefore = Files.readAllBytes(threePageDoc);

    Path watermarked = WatermarkRenderer.apply(threePageDoc,
        image(image, WatermarkPreset.Scope.ALL_PAGES), workDir, IOUtils.createTempFileOnlyStreamCache());

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
    for (WatermarkPreset.Rendering rendering : WatermarkPreset.Rendering.values()) {
      Path watermarked = WatermarkRenderer.apply(threePageDoc,
          new Watermark(image, WatermarkPreset.Scope.FIRST_PAGE, rendering), workDir,
          IOUtils.createTempFileOnlyStreamCache());

      assertThat(Stream.of(1, 2, 3).map(page -> Pdfs.imageCount(watermarked, page)).toList())
          .as("%s", rendering).containsExactly(1L, 0L, 0L);
    }
  }

  @Test
  void missingImageFailsInsteadOfBeingSwallowedAndLeavesNoOutput() {
    assertThatThrownBy(() -> WatermarkRenderer.apply(threePageDoc,
        image(tmp.resolve("no-such-image.png"), WatermarkPreset.Scope.ALL_PAGES), workDir,
        IOUtils.createTempFileOnlyStreamCache()))
        .isInstanceOf(IOException.class);
    assertThat(workDir).isEmptyDirectory();
  }

  @Test
  void assemblerWatermarksSourcePagesOnlyAndRemovesItsIntermediateFiles() throws IOException {
    byte[] sourceBefore = Files.readAllBytes(threePageDoc);
    AssemblyRequest request = new AssemblyRequest("Title of the bundle", "stitched.pdf",
        Optional.empty(), tocOnly().withSectionCoverSheets(true), false, Optional.empty(),
        Optional.of(image(image, WatermarkPreset.Scope.ALL_PAGES)),
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
