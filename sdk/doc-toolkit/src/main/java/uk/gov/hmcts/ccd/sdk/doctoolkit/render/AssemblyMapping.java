package uk.gov.hmcts.ccd.sdk.doctoolkit.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfPart;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfSection;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.AssemblyContent;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.AssemblyFolder;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.AssemblyItem;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.AssemblyNode;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.AssemblyRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.BundlePresentation;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.EmptySectionPage;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.MissingDocumentPage;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.Watermark;

/** Maps a request, with each part's converted content, to the assembler's model. */
final class AssemblyMapping {
  private AssemblyMapping() {
  }

  /**
   * The assembler request, and the part behind each item the assembler will place, in the order
   * it places them (null for an empty-section page).
   */
  record Mapped(AssemblyRequest request, List<PdfPart> itemParts) {
  }

  /**
   * The assembler request for {@code request}.
   *
   * @param contents each part's content, by identity
   */
  static Mapped map(PdfRequest request, Map<PdfPart, AssemblyContent> contents,
      Optional<Path> coverPage, Optional<Watermark> watermark, String outputFileName) {
    List<PdfPart> itemParts = new ArrayList<>();
    PdfSection root = request.root();
    List<AssemblyNode> items = children(root, contents, itemParts);
    if (items.isEmpty() && root.placeholderWhenEmpty()) {
      itemParts.add(null);
      items = List.of(new AssemblyItem(root.title(), Optional.empty(), false,
          new EmptySectionPage()));
    }
    BundlePresentation presentation = new BundlePresentation(request.tableOfContents(),
        request.sectionCoverSheets(), request.documentCoverSheets(), request.pageNumbers(),
        request.confidentialMarking());
    AssemblyRequest assembly = new AssemblyRequest(request.title(), outputFileName,
        Optional.empty(), presentation, request.titlePage(), coverPage, watermark, items);
    // Nulls mark empty-section pages, so List.copyOf (which rejects them) is not used.
    return new Mapped(assembly, Collections.unmodifiableList(itemParts));
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

  private static List<AssemblyNode> children(PdfSection section,
      Map<PdfPart, AssemblyContent> contents, List<PdfPart> itemParts) {
    List<AssemblyNode> nodes = new ArrayList<>();
    for (PdfPart part : section.parts()) {
      AssemblyContent content = contents.get(part);
      itemParts.add(part);
      nodes.add(new AssemblyItem(
          content instanceof MissingDocumentPage ? part.title() + " (missing)" : part.title(),
          part.date(), part.confidential(), content));
    }
    for (PdfSection child : section.sections()) {
      List<AssemblyNode> grandchildren = children(child, contents, itemParts);
      if (!grandchildren.isEmpty()) {
        nodes.add(new AssemblyFolder(child.title(), grandchildren));
      } else if (child.placeholderWhenEmpty()) {
        itemParts.add(null);
        nodes.add(new AssemblyItem(child.title(), Optional.empty(), false, new EmptySectionPage()));
      }
    }
    return nodes;
  }
}
