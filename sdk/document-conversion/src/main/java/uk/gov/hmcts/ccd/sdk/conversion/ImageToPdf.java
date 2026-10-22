package uk.gov.hmcts.ccd.sdk.conversion;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import uk.gov.hmcts.ccd.sdk.conversion.ConversionException.Reason;

/**
 * Draws an image onto PDF pages the way em-stitching-api does: one Letter page per image,
 * centred, scaled down to fit but never enlarged. A multi-page TIFF becomes one page per frame.
 */
public final class ImageToPdf {

  private ImageToPdf() {
  }

  /**
   * Writes the image as a PDF.
   *
   * @return the page count
   * @throws ConversionException with {@link Reason#INVALID_CONTENT} when the image cannot be
   *     decoded
   */
  public static int write(Path image, Path output) throws ConversionException {
    try (PDDocument document = new PDDocument()) {
      List<BufferedImage> frames = ImageFrames.tiffFrames(image);
      if (frames.size() > 1) {
        for (BufferedImage frame : frames) {
          addCentredPage(document, LosslessFactory.createFromImage(document, frame));
        }
      } else {
        addCentredPage(document, PDImageXObject.createFromFileByContent(image.toFile(), document));
      }
      document.save(output.toFile());
      return document.getNumberOfPages();
    } catch (IOException | IllegalArgumentException e) {
      throw new ConversionException(Reason.INVALID_CONTENT,
          "The image could not be decoded and rendered onto a PDF page", e);
    }
  }

  private static void addCentredPage(PDDocument document, PDImageXObject image)
      throws IOException {
    PDPage page = new PDPage();
    document.addPage(page);
    PDRectangle mediaBox = page.getMediaBox();
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
  }
}
