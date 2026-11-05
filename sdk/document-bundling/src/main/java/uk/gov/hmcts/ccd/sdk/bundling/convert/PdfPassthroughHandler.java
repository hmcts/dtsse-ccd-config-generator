package uk.gov.hmcts.ccd.sdk.bundling.convert;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandler;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandlingException;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandledDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandlerContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;
import uk.gov.hmcts.ccd.sdk.conversion.ConversionException;
import uk.gov.hmcts.ccd.sdk.conversion.PdfChecks;

public final class PdfPassthroughHandler implements DocumentHandler {
  @Override
  public HandledDocument handle(ResolvedDocument source, HandlerContext context)
      throws DocumentHandlingException {
    Path file = HandlerFiles.materialise(source, context, ".pdf");
    try {
      PdfChecks.requireUsablePdf(file, scratchDirectory(context));
    } catch (ConversionException e) {
      String message = e.reason() == ConversionException.Reason.ENCRYPTED
          ? e.getMessage() + "; encrypted documents cannot be stitched into a bundle"
          : e.getMessage();
      throw new DocumentHandlingException(message, e.getCause());
    }
    return HandledDocument.of(file);
  }

  private static Path scratchDirectory(HandlerContext context)
      throws DocumentHandlingException {
    try {
      Path probe = context.createTempFile(".scratch");
      Files.deleteIfExists(probe);
      return probe.getParent();
    } catch (IOException e) {
      throw new DocumentHandlingException("Could not allocate scratch space for PDF parsing", e);
    }
  }
}
