package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the merged bundle's bookmark tree, ported from {@code em-stitching-api}'s
 * {@code PDFOutline}.
 *
 * <p>Three deliberate, documented divergences from the current service:
 *
 * <ul>
 *   <li>Source outlines are preserved unconditionally. The service's {@code copyOutline}
 *       early-returns unless its {@code hasDocumentSubtitles} flag is set, silently dropping
 *       source outlines in the common case; the design commits to "existing source outlines
 *       nested beneath the corresponding document" with no such flag.</li>
 *   <li>Instead of grafting the source documents' outline COS objects into the merged document
 *       (the service's approach, which requires COS-key surgery and keeping every source open
 *       until the merged file is saved), source outlines are rebuilt as fresh
 *       {@link PDOutlineItem}s in the destination — same titles, same tree shape, same
 *       bold/italic styling, destinations remapped onto the merged pages, including
 *       {@code GoTo}-action outlines. A visited set guards against cyclic sibling/child
 *       references, and nesting deeper than {@link #MAX_COPY_DEPTH} levels is truncated (the
 *       copy reports it so the assembler can emit a warning naming the document) instead of
 *       recursing unboundedly — a 100,000-level malicious outline must not kill the JVM stack.
 *       Sibling traversal is iterative, so sibling count is unbounded.</li>
 *   <li>Named destinations are resolved to explicit page destinations at copy time via the
 *       source catalog. The service copies the name reference but never copies a names
 *       dictionary into the merged document, so its copied named destinations dangle. An
 *       unresolvable name (absent names dictionary) yields a bookmark without a destination
 *       rather than one that navigates nowhere.</li>
 * </ul>
 */
final class OutlineBuilder {

  /** Outline nesting deeper than this is truncated; no legitimate document comes close. */
  static final int MAX_COPY_DEPTH = 100;

  private static final Logger log = LoggerFactory.getLogger(OutlineBuilder.class);
  private static final int MAX_TITLE_LENGTH = 400;

  private final PDDocument document;
  private final PDOutlineItem root;

  /**
   * Creates the document outline with a single root item for the bundle. The root's destination
   * is set once the first page exists, via {@link #setRootDestination()}.
   *
   * @param document the merged document
   * @param bundleTitle the bundle title used for the root bookmark
   */
  OutlineBuilder(PDDocument document, String bundleTitle) {
    this.document = document;
    PDDocumentOutline outline = new PDDocumentOutline();
    document.getDocumentCatalog().setDocumentOutline(outline);
    outline.openNode();
    this.root = new PDOutlineItem();
    root.setTitle(trimTitle(bundleTitle));
    outline.addLast(root);
  }

  PDOutlineItem root() {
    return root;
  }

  /**
   * Adds a bold bookmark pointing at a page of the merged document.
   *
   * @param parent the parent bookmark
   * @param title the bookmark title, trimmed to the same length as the current service
   * @param pageIndex the 0-based destination page, which must already exist
   * @return the created bookmark, usable as a parent for nested items
   */
  PDOutlineItem addItem(PDOutlineItem parent, String title, int pageIndex) {
    PDOutlineItem item = new PDOutlineItem();
    item.setDestination(document.getPage(pageIndex));
    item.setTitle(trimTitle(title));
    item.setBold(true);
    parent.addLast(item);
    return item;
  }

  /**
   * Points the root bookmark at the first page of the merged document.
   */
  void setRootDestination() {
    root.setDestination(document.getPage(0));
  }

  /**
   * Rebuilds a source document's outline underneath {@code destParent}, remapping every
   * resolvable destination onto the merged document's pages.
   *
   * <p>The outline is passed explicitly because the assembler detaches it from the source
   * catalog before appending pages, so the merge utility does not graft it onto the merged
   * document's own outline.
   *
   * @param destParent the bookmark of the document the outline belongs to
   * @param sourceOutline the source document's outline, or null when it has none
   * @param sourceCatalog the source document's catalog, used to resolve named destinations
   * @param pageOffset the 0-based index in the merged document of the source's first page
   * @return whether any part of the source outline was dropped (a cycle was detected or the
   *     nesting exceeded {@link #MAX_COPY_DEPTH})
   */
  boolean copySourceOutline(PDOutlineItem destParent, PDDocumentOutline sourceOutline,
      PDDocumentCatalog sourceCatalog, int pageOffset) {
    if (sourceOutline == null) {
      return false;
    }
    Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    boolean truncated = false;
    PDOutlineItem child = sourceOutline.getFirstChild();
    while (child != null) {
      if (!visited.add(child.getCOSObject())) {
        log.warn("Circular reference detected in source outline; truncating");
        return true;
      }
      truncated |= copyNode(child, destParent, sourceCatalog, pageOffset, 0, visited);
      child = child.getNextSibling();
    }
    return truncated;
  }

  private boolean copyNode(PDOutlineItem sourceItem, PDOutlineItem destParent,
      PDDocumentCatalog sourceCatalog, int pageOffset, int depth, Set<COSDictionary> visited) {
    PDOutlineItem copy = new PDOutlineItem();
    String title = sourceItem.getTitle();
    copy.setTitle(title == null ? "   " : trimTitle(title));
    copy.setBold(sourceItem.isBold());
    copy.setItalic(sourceItem.isItalic());
    int sourcePage = resolveSourcePage(sourceItem, sourceCatalog);
    if (sourcePage >= 0 && sourcePage + pageOffset < document.getNumberOfPages()) {
      copy.setDestination(document.getPage(sourcePage + pageOffset));
    }
    destParent.addLast(copy);
    if (sourceItem.getFirstChild() != null && depth + 1 >= MAX_COPY_DEPTH) {
      log.warn("Source outline nesting exceeds {} levels; truncating", MAX_COPY_DEPTH);
      return true;
    }
    boolean truncated = false;
    PDOutlineItem child = sourceItem.getFirstChild();
    while (child != null) {
      if (!visited.add(child.getCOSObject())) {
        log.warn("Circular reference detected in source outline; truncating");
        return true;
      }
      truncated |= copyNode(child, copy, sourceCatalog, pageOffset, depth + 1, visited);
      child = child.getNextSibling();
    }
    return truncated;
  }

  /**
   * Resolves the 0-based source page an outline item points at, following {@code GoTo} actions
   * and named destinations exactly as the current service's {@code PDFOutline#getOutlinePage}.
   *
   * @param item the source outline item
   * @param catalog the source document's catalog, used to resolve named destinations
   * @return the 0-based source page index, or -1 when unresolvable
   */
  private int resolveSourcePage(PDOutlineItem item, PDDocumentCatalog catalog) {
    try {
      PDDestination destination = item.getDestination();
      if (destination == null && item.getAction() instanceof PDActionGoTo goTo) {
        destination = goTo.getDestination();
      }
      if (destination instanceof PDNamedDestination named) {
        destination = catalog.findNamedDestinationPage(named);
      }
      if (destination instanceof PDPageDestination pageDestination) {
        return Math.max(pageDestination.retrievePageNumber(), 0);
      }
    } catch (Exception e) {
      log.warn("Could not resolve outline destination: {}", e.toString());
    }
    return -1;
  }

  /**
   * Trims a title to the service's 400-character bookmark limit, never cutting a surrogate
   * pair in half (an unpaired surrogate is an invalid PDF text string that readers render as a
   * replacement glyph).
   *
   * @param title the raw title
   * @return the trimmed title
   */
  private String trimTitle(String title) {
    if (title.length() <= MAX_TITLE_LENGTH) {
      return title;
    }
    int cut = MAX_TITLE_LENGTH - 1;
    if (Character.isHighSurrogate(title.charAt(cut - 1))) {
      cut--;
    }
    return title.substring(0, cut) + "...";
  }
}
