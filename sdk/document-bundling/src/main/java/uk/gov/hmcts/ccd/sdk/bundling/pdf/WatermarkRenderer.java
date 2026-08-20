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

/**
 * Applies an approved watermark preset to a source document, ported from
 * {@code em-stitching-api}'s {@code PDFWatermark} (PDFBox {@code Overlay} of a single overlay
 * page onto the first or every page, in the foreground or background).
 *
 * <p>Three deliberate divergences from the current service. The source file is never mutated —
 * the watermarked copy is written to a fresh file under the job's working directory (the service
 * saves the overlay back over its input file). The output is written to a <b>different</b> path
 * from the one being read: PDFBox loads lazily, and saving over the file still being read — as
 * the service does — truncates compressed streams and destroys the page's extractable text
 * layer, which is why the current service's watermarked output has corrupt text in production.
 * And failures propagate instead of being logged and swallowed, so a requested legal marking can
 * never silently go missing from a published bundle.
 */
final class WatermarkRenderer {

  private WatermarkRenderer() {
  }

  /**
   * Applies the watermark to {@code source}, writing the result to a new file under the working
   * directory.
   *
   * @param source the source PDF; read only, never modified
   * @param watermark the approved watermark preset
   * @param workDir the job's working directory for the watermarked output
   * @param streamCache the bounded stream cache configuration
   * @param fonts the fonts of this assembly, used by the text watermark
   * @return the watermarked output under {@code workDir}
   * @throws IOException if the watermark cannot be applied
   */
  static Path apply(Path source, Watermark watermark, Path workDir,
      StreamCacheCreateFunction streamCache, PdfFonts fonts) throws IOException {
    Path output = Files.createTempFile(workDir, "watermarked-", ".pdf");
    try {
      applyTo(source, output, watermark, streamCache, fonts);
      return output;
    } catch (IOException | RuntimeException e) {
      Files.deleteIfExists(output);
      throw e;
    }
  }

  private static void applyTo(Path source, Path output, Watermark watermark,
      StreamCacheCreateFunction streamCache, PdfFonts fonts) throws IOException {
    try (PDDocument document = Loader.loadPDF(source.toFile(), streamCache);
        PDDocument overlayDocument = new PDDocument()) {
      PDPage overlayPage = new PDPage();
      overlayDocument.addPage(overlayPage);
      drawOverlay(overlayDocument, overlayPage, watermark, fonts);
      try (Overlay overlay = new Overlay()) {
        overlay.setInputPDF(document);
        overlay.setOverlayPosition(watermark.rendering() == Watermark.Rendering.OPAQUE
            ? Overlay.Position.FOREGROUND
            : Overlay.Position.BACKGROUND);
        if (watermark.scope() == Watermark.Scope.ALL_PAGES) {
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
      Watermark watermark, PdfFonts fonts) throws IOException {
    PDRectangle mediaBox = overlayPage.getMediaBox();
    if (watermark.image().isPresent()) {
      PDImageXObject image = PDImageXObject.createFromFileByExtension(
          watermark.image().get().toFile(), overlayDocument);
      float startX = (mediaBox.getWidth() - image.getWidth()) / 2;
      float startY = (mediaBox.getHeight() - image.getHeight()) / 2;
      try (PDPageContentStream contentStream =
          new PDPageContentStream(overlayDocument, overlayPage)) {
        contentStream.drawImage(image, startX, startY);
      }
    } else {
      String text = watermark.text().orElseThrow();
      float fontSize = 40;
      float stringWidth = PdfUtility.getStringWidth(text, fonts.helveticaBold(), fontSize);
      float startX = Math.max((mediaBox.getWidth() - stringWidth) / 2, 20);
      float startY = mediaBox.getHeight() / 2;
      try (PDPageContentStream contentStream =
          new PDPageContentStream(overlayDocument, overlayPage)) {
        contentStream.setNonStrokingColor(0.8f);
        contentStream.beginText();
        contentStream.setFont(fonts.helveticaBold(), fontSize);
        contentStream.newLineAtOffset(startX, startY);
        contentStream.showText(PdfUtility.sanitizeText(text));
        contentStream.endText();
      }
    }
  }
}
