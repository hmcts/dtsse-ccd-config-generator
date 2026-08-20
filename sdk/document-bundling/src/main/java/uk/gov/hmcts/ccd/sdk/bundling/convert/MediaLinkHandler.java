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

/**
 * The built-in handler for audio and video documents: renders the standard generated media link
 * page — title, date, media type, optional duration and note, and a clickable link to the
 * consumer-supplied access URL — as a single-page PDF.
 *
 * <p>Media documents are never fetched, so the {@link ResolvedDocument} this handler receives is
 * the pipeline's metadata-only synthesis: it carries the media type declared in the request and
 * no content. Everything the page needs comes from {@link HandlerContext#document()} — the owning
 * {@link BundleDocument}'s title, date, and {@link MediaPlaceholder}. A consumer replacement (a
 * branded media page, for example) reads the same context, so metadata-only documents flow
 * through the one handler SPI.
 *
 * <p>The page itself is drawn by the pdf package's deterministic template: the handler runs a
 * minimal single-item assembly (no contents, cover sheets, title page, numbering, or marking)
 * whose only content is the {@link MediaLinkPage}, so the generated page is pixel-identical to
 * the one the assembler would draw natively and there is exactly one template to test.
 */
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
          List.of(page));
      assembler.assemble(request, output.getParent());
      return HandledDocument.of(output);
    } catch (IOException e) {
      throw new DocumentHandlingException("The media link page could not be generated", e);
    }
  }
}
