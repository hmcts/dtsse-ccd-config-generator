package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.EmptySectionPolicy;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyFolder;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyItem;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyNode;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyRequest;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.EmptySectionPage;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.PdfSource;

/**
 * Step 7's first half: mapping the request tree plus the handled PDFs onto an
 * {@link AssemblyRequest}.
 *
 * <p>The root section is the invisible container, not a rendered folder: its direct documents
 * become top-level items and its child sections become top-level folders — exactly the split the
 * {@code CcdBundle} output echoes as {@code documents} (outside any folder) and {@code folders}.
 * Within a section, documents render first in list order, then child sections in list order.
 *
 * <p>Empty sections follow their policy: {@code OMIT} drops the section entirely;
 * {@code INCLUDE_PLACEHOLDER} renders the standard visible empty-section page in the section's
 * position, titled with the section title, participating in contents, bookmarks and pagination
 * like any document. A section counts as empty when it has no documents and no child section
 * contributes anything renderable.
 *
 * <p>The mapper also returns the item origins, index-aligned with the assembler's
 * {@code AssemblyResult.items()}, so each document's start page and page count can be attributed
 * back to its request document; placeholder items carry a null document id.
 */
final class AssemblyMapping {

  /**
   * One assembly item's origin: the request document it renders, or a placeholder.
   *
   * @param document the request document, or null for an empty-section placeholder item
   */
  record Origin(BundleDocument document) {
  }

  /**
   * The mapped assembly request plus the item origins in render order.
   *
   * @param request the assembly request
   * @param origins the origin of each assembly item, index-aligned with the assembler's result
   */
  record Mapped(AssemblyRequest request, List<Origin> origins) {
  }

  private AssemblyMapping() {
  }

  /**
   * Maps the request tree and the handled PDFs onto an assembly request.
   *
   * @param request the bundle request
   * @param handledPdfs each document's converted PDF, by document id
   * @return the assembly request and the item origins in render order
   */
  static Mapped map(BundleRequest request, Map<String, Path> handledPdfs) {
    List<Origin> origins = new ArrayList<>();
    List<AssemblyNode> items = mapChildren(request.root(), handledPdfs, origins);
    AssemblyRequest assembly = new AssemblyRequest(
        request.title(),
        request.fileName(),
        Optional.empty(),
        request.presentation(),
        true,
        Optional.empty(),
        items);
    return new Mapped(assembly, List.copyOf(origins));
  }

  private static List<AssemblyNode> mapChildren(
      BundleSection section, Map<String, Path> handledPdfs, List<Origin> origins) {
    List<AssemblyNode> nodes = new ArrayList<>();
    for (BundleDocument document : section.documents()) {
      nodes.add(new AssemblyItem(
          document.title(),
          document.date(),
          document.confidential(),
          new PdfSource(handledPdfs.get(document.id()))));
      origins.add(new Origin(document));
    }
    for (BundleSection child : section.sections()) {
      mapSection(child, handledPdfs, origins).ifPresent(nodes::add);
    }
    return nodes;
  }

  private static Optional<AssemblyNode> mapSection(
      BundleSection section, Map<String, Path> handledPdfs, List<Origin> origins) {
    List<Origin> childOrigins = new ArrayList<>();
    List<AssemblyNode> children = mapChildren(section, handledPdfs, childOrigins);
    if (!children.isEmpty()) {
      origins.addAll(childOrigins);
      return Optional.of(new AssemblyFolder(section.title(), children));
    }
    if (section.emptySectionPolicy() == EmptySectionPolicy.INCLUDE_PLACEHOLDER) {
      origins.add(new Origin(null));
      return Optional.of(new AssemblyItem(
          section.title(), Optional.empty(), false, new EmptySectionPage()));
    }
    return Optional.empty();
  }
}
