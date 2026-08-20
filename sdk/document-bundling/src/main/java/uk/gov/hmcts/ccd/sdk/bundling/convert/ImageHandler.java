package uk.gov.hmcts.ccd.sdk.bundling.convert;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandler;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandlingException;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandledDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandlerContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;

/**
 * The built-in raster-image handler, a direct port of {@code em-stitching-api}'s
 * {@code ImageConverter}: the image is drawn centred on a single default-sized (US letter) PDF
 * page, scaled down proportionally when it exceeds the page, and never scaled up — so pixel
 * dimensions map onto the page exactly as the current microservice maps them.
 *
 * <p>The image is decoded from content ({@code PDImageXObject.createFromFileByContent}), not from
 * the declared type or file extension, so a mislabelled PNG still converts and genuinely
 * undecodable content fails with a typed error naming the document. As in the current service,
 * {@code image/svg+xml} is registered but not decodable by PDFBox; an actual SVG source fails
 * typed rather than silently rendering wrong.
 */
public final class ImageHandler implements DocumentHandler {

  @Override
  public HandledDocument handle(ResolvedDocument source, HandlerContext context)
      throws DocumentHandlingException {
    Path file = HandlerFiles.materialise(source, context, ".img");
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);

      PDRectangle mediaBox = page.getMediaBox();
      PDImageXObject image = PDImageXObject.createFromFileByContent(file.toFile(), document);
      float scale = Math.min(1f, Math.min(
          mediaBox.getWidth() / image.getWidth(),
          mediaBox.getHeight() / image.getHeight()));
      int width = (int) (image.getWidth() * scale);
      int height = (int) (image.getHeight() * scale);
      float startX = (mediaBox.getWidth() - width) / 2;
      float startY = (mediaBox.getHeight() - height) / 2;
      try (PDPageContentStream contents = new PDPageContentStream(document, page)) {
        contents.drawImage(image, startX, startY, width, height);
      }

      Path output = context.createTempFile(".pdf");
      document.save(output.toFile());
      return HandledDocument.of(output);
    } catch (IOException | IllegalArgumentException e) {
      throw new DocumentHandlingException(
          "The image could not be decoded and rendered onto a PDF page", e);
    }
  }
}
