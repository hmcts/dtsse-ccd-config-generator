package uk.gov.hmcts.ccd.sdk.bundling.convert;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.CCITTFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandler;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandlingException;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandledDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandlerContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;

public final class ImageHandler implements DocumentHandler {
  @Override
  public HandledDocument handle(ResolvedDocument source, HandlerContext context)
      throws DocumentHandlingException {
    Path file = HandlerFiles.materialise(source, context, ".img");
    try (PDDocument document = new PDDocument()) {
      if (!addTiffFrames(document, file)) {
        addPage(document, PDImageXObject.createFromFileByContent(file.toFile(), document));
      }

      Path output = context.createTempFile(".pdf");
      document.save(output.toFile());
      return HandledDocument.of(output);
    } catch (IOException | IllegalArgumentException e) {
      throw new DocumentHandlingException(
          "The image could not be decoded and rendered onto a PDF page", e);
    }
  }

  // Adds one page per frame of a multi-page TIFF, decoding a frame at a time. Returns false for
  // anything else, which keeps the single-image path.
  private static boolean addTiffFrames(PDDocument document, Path file) throws IOException {
    try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
      if (input == null) {
        return false;
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) {
        return false;
      }
      ImageReader reader = readers.next();
      try {
        if (!"tif".equalsIgnoreCase(reader.getFormatName())
            && !"tiff".equalsIgnoreCase(reader.getFormatName())) {
          return false;
        }
        reader.setInput(input, false, true);
        int frames = reader.getNumImages(true);
        if (frames <= 1) {
          return false;
        }
        for (int frame = 0; frame < frames; frame++) {
          addPage(document, tiffFrame(document, file, reader, frame));
        }
        return true;
      } finally {
        reader.dispose();
      }
    }
  }

  // Same preference as PDImageXObject.createFromFileByContent: keep CCITT data compressed as-is,
  // otherwise decode the frame and embed it losslessly.
  private static PDImageXObject tiffFrame(PDDocument document, Path file, ImageReader reader, int frame)
      throws IOException {
    try {
      PDImageXObject image = CCITTFactory.createFromFile(document, file.toFile(), frame);
      if (image != null) {
        return image;
      }
    } catch (IOException notCcitt) {
      // Fall through to a full decode of this frame.
    }
    return LosslessFactory.createFromImage(document, reader.read(frame));
  }

  private static void addPage(PDDocument document, PDImageXObject image) throws IOException {
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
