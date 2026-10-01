package uk.gov.hmcts.ccd.sdk.bundling.convert;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlePresentation;
import uk.gov.hmcts.ccd.sdk.bundling.api.ConfidentialMarking;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandler;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandlingException;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandledDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandlerContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.MediaPlaceholder;
import uk.gov.hmcts.ccd.sdk.bundling.api.PageNumbers;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyItem;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyRequest;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.MediaLinkPage;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.PdfBundleAssembler;

public final class MediaLinkHandler implements DocumentHandler {
  private static final BundlePresentation BARE_PRESENTATION = new BundlePresentation(
      false, false, false, PageNumbers.NONE, ConfidentialMarking.NONE);

  private final PdfBundleAssembler assembler = new PdfBundleAssembler();

  @Override
  public HandledDocument handle(ResolvedDocument source, HandlerContext context)
      throws DocumentHandlingException {
    BundleDocument document = context.document();
    MediaPlaceholder media = document.media().orElseThrow(() -> new DocumentHandlingException(
        "The document has no media placeholder; the media link handler only handles documents "
            + "built with BundleDocument.builder().media(...)"));
    try {
      Path output = context.createTempFile(".pdf");
      Files.deleteIfExists(output);
      AssemblyItem page = new AssemblyItem(
          document.title(),
          document.date(),
          false,
          new MediaLinkPage(source.mediaType(), media));
      AssemblyRequest request = new AssemblyRequest(
          document.title(),
          output.getFileName().toString(),
          Optional.empty(),
          BARE_PRESENTATION,
          false,
          Optional.empty(),
          Optional.empty(),
          List.of(page));
      assembler.assemble(request, output.getParent());
      return HandledDocument.generated(output);
    } catch (IOException e) {
      throw new DocumentHandlingException("The media link page could not be generated", e);
    }
  }
}
