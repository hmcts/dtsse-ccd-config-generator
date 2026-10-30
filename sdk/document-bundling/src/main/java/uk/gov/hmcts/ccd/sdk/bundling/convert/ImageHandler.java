package uk.gov.hmcts.ccd.sdk.bundling.convert;

import java.io.IOException;
import java.nio.file.Path;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandler;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandlingException;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandledDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandlerContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;
import uk.gov.hmcts.ccd.sdk.conversion.ConversionException;
import uk.gov.hmcts.ccd.sdk.conversion.ImageToPdf;

public final class ImageHandler implements DocumentHandler {
  @Override
  public HandledDocument handle(ResolvedDocument source, HandlerContext context)
      throws DocumentHandlingException {
    Path file = HandlerFiles.materialise(source, context, ".img");
    Path output;
    try {
      output = context.createTempFile(".pdf");
    } catch (IOException e) {
      throw new DocumentHandlingException("Could not allocate the converted image's PDF", e);
    }
    try {
      ImageToPdf.write(file, output);
    } catch (ConversionException e) {
      throw new DocumentHandlingException(e.getMessage(), e.getCause());
    }
    return HandledDocument.of(output);
  }
}
