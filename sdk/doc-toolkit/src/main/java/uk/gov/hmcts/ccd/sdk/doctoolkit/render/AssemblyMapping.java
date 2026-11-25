package uk.gov.hmcts.ccd.sdk.doctoolkit.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.doctoolkit.BundleDocument;
import uk.gov.hmcts.ccd.sdk.doctoolkit.BundleRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.BundleSection;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.AssemblyContent;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.AssemblyFolder;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.AssemblyItem;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.AssemblyNode;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.AssemblyRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.BundlePresentation;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.EmptySectionPage;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.MissingDocumentPage;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.Watermark;

/** Maps a bundle request, with each document's converted content, to the assembler's model. */
final class AssemblyMapping {
  private AssemblyMapping() {
  }

  /**
   * The assembler request for {@code request}.
   *
   * @param contents each document's content, by identity
   */
  static AssemblyRequest map(BundleRequest request, Map<BundleDocument, AssemblyContent> contents,
      Optional<Path> coverPage, Optional<Watermark> watermark, String outputFileName) {
    BundleSection root = request.root();
    List<AssemblyNode> items = children(root, contents);
    if (items.isEmpty() && root.placeholderWhenEmpty()) {
      items = List.of(new AssemblyItem(root.title(), Optional.empty(), false,
          new EmptySectionPage()));
    }
    BundlePresentation presentation = new BundlePresentation(request.tableOfContents(),
        request.sectionCoverSheets(), request.documentCoverSheets(), request.pageNumbers(),
        request.confidentialMarking());
    return new AssemblyRequest(request.title(), outputFileName, Optional.empty(), presentation,
        true, coverPage, watermark, items);
  }

  /** The request's watermark, its image written to the job directory for the assembler. */
  static Optional<Watermark> watermark(Optional<uk.gov.hmcts.ccd.sdk.doctoolkit.Watermark> watermark,
      Path job) throws IOException {
    if (watermark.isEmpty()) {
      return Optional.empty();
    }
    byte[] image = watermark.get().image();
    // PDFBox picks the decoder from the extension.
    boolean png = image.length > 3 && (image[0] & 0xFF) == 0x89 && image[1] == 'P' && image[2] == 'N';
    Path file = JobDirectory.createFile(job, png ? ".png" : ".jpg");
    Files.write(file, image);
    return Optional.of(new Watermark(file, watermark.get().firstPageOnly(),
        watermark.get().behindContent()));
  }

  private static List<AssemblyNode> children(BundleSection section,
      Map<BundleDocument, AssemblyContent> contents) {
    List<AssemblyNode> nodes = new ArrayList<>();
    for (BundleDocument document : section.documents()) {
      AssemblyContent content = contents.get(document);
      nodes.add(new AssemblyItem(
          content instanceof MissingDocumentPage ? document.title() + " (missing)" : document.title(),
          document.date(), document.confidential(), content));
    }
    for (BundleSection child : section.sections()) {
      List<AssemblyNode> grandchildren = children(child, contents);
      if (!grandchildren.isEmpty()) {
        nodes.add(new AssemblyFolder(child.title(), grandchildren));
      } else if (child.placeholderWhenEmpty()) {
        nodes.add(new AssemblyItem(child.title(), Optional.empty(), false, new EmptySectionPage()));
      }
    }
    return nodes;
  }
}
