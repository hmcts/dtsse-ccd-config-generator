package uk.gov.hmcts.ccd.sdk.bundling.convert;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandler;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandlingException;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandledDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandlerContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderException;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;

public final class DocmosisOfficeHandler implements DocumentHandler {
  @Override
  public HandledDocument handle(ResolvedDocument source, HandlerContext context)
      throws DocumentHandlingException {
    Path file = HandlerFiles.materialise(source, context, ".office");
    DocmosisRenderService docmosis = context.docmosis().orElseThrow(
        () -> new DocumentHandlingException(
            "The Docmosis render service is not configured; office documents cannot be "
                + "converted"));
    Path converted;
    try {
      converted = docmosis.convertToPdf(file, source.fileName(), source.mediaType());
    } catch (DocmosisRenderException e) {
      throw new DocumentHandlingException("Docmosis conversion failed: " + e.getMessage(), e);
    }
    // Move the converted PDF into the job's temporary directory so the job's cleanup owns it;
    // on any failure the client's output file must not linger in its shared directory.
    try {
      Path output = context.createTempFile(".pdf");
      Files.move(converted, output, StandardCopyOption.REPLACE_EXISTING);
      return HandledDocument.of(output);
    } catch (IOException e) {
      try {
        Files.deleteIfExists(converted);
      } catch (IOException ignored) {
        // Best effort: the original failure is the one worth reporting.
      }
      throw new DocumentHandlingException(
          "The converted PDF could not be moved into the job's temporary directory", e);
    }
  }

}
