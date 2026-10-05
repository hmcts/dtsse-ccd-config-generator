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
