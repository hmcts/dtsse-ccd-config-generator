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

final class OutlineBuilder {
  static final int MAX_COPY_DEPTH = 100;

  private static final Logger log = LoggerFactory.getLogger(OutlineBuilder.class);
  private static final int MAX_TITLE_LENGTH = 400;

  private final PDDocument document;
  private final PDOutlineItem root;

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

  PDOutlineItem addItem(PDOutlineItem parent, String title, int pageIndex) {
    PDOutlineItem item = new PDOutlineItem();
    item.setDestination(document.getPage(pageIndex));
    item.setTitle(trimTitle(title));
    item.setBold(true);
    parent.addLast(item);
    return item;
  }

  void setRootDestination() {
    root.setDestination(document.getPage(0));
  }

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
        return pageDestination.retrievePageNumber();
      }
    } catch (Exception e) {
      log.warn("Could not resolve outline destination: {}", e.toString());
    }
    return -1;
  }

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
