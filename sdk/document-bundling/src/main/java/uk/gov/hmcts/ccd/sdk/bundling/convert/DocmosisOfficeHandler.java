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

/**
 * The built-in office-format handler: converts Word, Excel, PowerPoint, RTF, and plain-text
 * sources to PDF through the shared Docmosis render service configured on the renderer.
 *
 * <p>The source is sent with its real file name and media type — the current stitching service
 * tags every upload {@code application/pdf} regardless of its actual type, a documented defect
 * this module deliberately does not replicate. {@code BundleLimits.maxOfficeSourceBytesPerDocument}
 * is enforced before any network call. Docmosis failures are mapped to typed conversion failures
 * whose detail carries the sanitised Docmosis message and, for transient infrastructure failures,
 * says so explicitly so durable jobs can decide to retry; the raw response body and access key
 * never appear.
 */
public final class DocmosisOfficeHandler implements DocumentHandler {

  @Override
  public HandledDocument handle(ResolvedDocument source, HandlerContext context)
      throws DocumentHandlingException {
    Path file = HandlerFiles.materialise(source, context, ".office");
    enforceOfficeSizeLimit(file, context);
    DocmosisRenderService docmosis = context.docmosis().orElseThrow(
        () -> new DocumentHandlingException(
            "The Docmosis render service is not configured; office documents cannot be "
                + "converted"));
    try {
      Path converted = docmosis.convertToPdf(file, source.fileName(), source.mediaType());
      // Move the converted PDF into the job's temporary directory so the job's cleanup owns it.
      Path output = context.createTempFile(".pdf");
      Files.move(converted, output, StandardCopyOption.REPLACE_EXISTING);
      return HandledDocument.of(output);
    } catch (DocmosisRenderException e) {
      String prefix = e.isTransientFailure()
          ? "Docmosis conversion failed transiently (safe to retry within bounds): "
          : "Docmosis conversion failed: ";
      throw new DocumentHandlingException(prefix + e.getMessage(), e);
    } catch (IOException e) {
      throw new DocumentHandlingException(
          "The converted PDF could not be moved into the job's temporary directory", e);
    }
  }

  private static void enforceOfficeSizeLimit(Path file, HandlerContext context)
      throws DocumentHandlingException {
    long limit = context.limits().maxOfficeSourceBytesPerDocument();
    long size;
    try {
      size = Files.size(file);
    } catch (IOException e) {
      throw new DocumentHandlingException("Could not read the spooled source file size", e);
    }
    if (size > limit) {
      throw new DocumentHandlingException(
          "The office source is " + size + " bytes, which exceeds the office conversion limit "
              + "of " + limit + " bytes (BundleLimits.maxOfficeSourceBytesPerDocument)");
    }
  }
}
