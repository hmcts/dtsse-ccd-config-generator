package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlePresentation;
import uk.gov.hmcts.ccd.sdk.bundling.api.ConfidentialMarking;
import uk.gov.hmcts.ccd.sdk.bundling.api.MediaPlaceholder;
import uk.gov.hmcts.ccd.sdk.bundling.api.PageNumbers;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyItem;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyRequest;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.MediaLinkPage;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.PdfBundleAssembler;

/**
 * Builds the page that stands in for an audio or video document. The recording is never fetched:
 * the page comes from the document's title, date and {@link MediaPlaceholder}. This is a bundle
 * concept rather than a conversion, so it is not a document-conversion converter.
 */
final class MediaLinkPages {
  private static final BundlePresentation BARE_PRESENTATION = new BundlePresentation(
      false, false, false, PageNumbers.NONE, ConfidentialMarking.NONE);

  private final PdfBundleAssembler assembler = new PdfBundleAssembler();

  /**
   * Writes the document's link page into {@code jobDirectory}, returning the PDF.
   */
  Path render(BundleDocument document, String mediaType, Path jobDirectory) throws IOException {
    MediaPlaceholder media = document.media().orElseThrow();
    Path output = JobDirectory.createFile(jobDirectory, ".pdf");
    Files.deleteIfExists(output);
    AssemblyItem page = new AssemblyItem(
        document.title(),
        document.date(),
        false,
        new MediaLinkPage(mediaType, media));
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
    return output;
  }
}
