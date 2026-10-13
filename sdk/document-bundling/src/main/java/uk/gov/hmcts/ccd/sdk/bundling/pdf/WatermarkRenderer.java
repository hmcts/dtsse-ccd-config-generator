package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessStreamCache.StreamCacheCreateFunction;
import org.apache.pdfbox.multipdf.Overlay;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import uk.gov.hmcts.ccd.sdk.bundling.api.WatermarkPreset;

final class WatermarkRenderer {
  private WatermarkRenderer() {
  }

  static Path apply(Path source, Watermark watermark, Path workDir,
      StreamCacheCreateFunction streamCache) throws IOException {
    Path output = Files.createTempFile(workDir, "watermarked-", ".pdf");
    try {
      applyTo(source, output, watermark, streamCache);
      return output;
    } catch (IOException | RuntimeException e) {
      Files.deleteIfExists(output);
      throw e;
    }
  }

  private static void applyTo(Path source, Path output, Watermark watermark,
      StreamCacheCreateFunction streamCache) throws IOException {
    try (PDDocument document = Loader.loadPDF(source.toFile(), streamCache);
        PDDocument overlayDocument = new PDDocument()) {
      PDPage overlayPage = new PDPage();
      overlayDocument.addPage(overlayPage);
      drawOverlay(overlayDocument, overlayPage, watermark);
      try (Overlay overlay = new Overlay()) {
        overlay.setInputPDF(document);
        overlay.setOverlayPosition(watermark.rendering() == WatermarkPreset.Rendering.OPAQUE
            ? Overlay.Position.FOREGROUND
            : Overlay.Position.BACKGROUND);
        if (watermark.scope() == WatermarkPreset.Scope.ALL_PAGES) {
          overlay.setAllPagesOverlayPDF(overlayDocument);
        } else {
          overlay.setFirstPageOverlayPDF(overlayDocument);
        }
        overlay.overlay(new HashMap<>());
      }
      // Never the path being read: the lazy reader must survive until the save completes.
      document.save(output.toFile());
    }
  }

  private static void drawOverlay(PDDocument overlayDocument, PDPage overlayPage,
      Watermark watermark) throws IOException {
    PDRectangle mediaBox = overlayPage.getMediaBox();
    PDImageXObject image = PDImageXObject.createFromFileByExtension(
        watermark.image().toFile(), overlayDocument);
    float startX = (mediaBox.getWidth() - image.getWidth()) / 2;
    float startY = (mediaBox.getHeight() - image.getHeight()) / 2;
    try (PDPageContentStream contentStream =
        new PDPageContentStream(overlayDocument, overlayPage)) {
      contentStream.drawImage(image, startX, startY);
    }
  }
}
