package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

final class OutlineBuilder {
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
