package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

final class TocRenderer {
  static final String INDEX_PAGE = "Index Page";

  private static final int NUM_LINES_PER_PAGE = 38;
  private static final float TOP_MARGIN_OFFSET = 40f;
  private static final int SPACE_PER_LINE = 500;
  private static final int SPACE_PER_TITLE_LINE = 400;
  private static final int TITLE_XX_OFFSET = 50;
  private static final int DATE_XX_OFFSET = 460;
  private static final int PAGE_XX_OFFSET = 545;
  private static final int FOLDER_FONT_SIZE = 13;
  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);

  private final PDDocument document;
  private final PdfFonts fonts;
  private final List<PDPage> pages = new ArrayList<>();
  private int numLinesAdded;
  private boolean endOfFolder;

  TocRenderer(PDDocument document, AssemblyRequest request, PdfFonts fonts) throws IOException {
    this.document = document;
    this.fonts = fonts;
    String description = request.description().orElse("");
    int noOfPages = estimatePages(request);
    for (int i = 0; i < noOfPages; i++) {
      PDPage page = new PDPage();
      pages.add(page);
      document.addPage(page);
    }

    if (!description.isEmpty()) {
      PdfUtility.addText(document, getPage(), description, 50, 80, fonts.helvetica(), 12,
          SPACE_PER_LINE);
    }
    int descriptionLines =
        PdfUtility.splitString(description, SPACE_PER_LINE, fonts.helvetica(), 12).length;
    int indexVerticalOffset = Math.max(descriptionLines * 20 + 70, 90);
    PdfUtility.addCenterText(document, getPage(), INDEX_PAGE, indexVerticalOffset,
        fonts.helveticaBold(), 14);

    int headerVerticalOffset = indexVerticalOffset + 30;
    PdfUtility.addText(document, getPage(), "Date", DATE_XX_OFFSET, headerVerticalOffset,
        fonts.helvetica(), 12, SPACE_PER_LINE);
    PdfUtility.addText(document, getPage(), "Page", PAGE_XX_OFFSET, headerVerticalOffset,
        fonts.helvetica(), 12, SPACE_PER_LINE);

    numLinesAdded = initialLines(description, fonts);
  }

  void addDocument(String title, Optional<LocalDate> date, int pageIndex) throws IOException {
    addSpaceAfterFolder();
    float yyOffset = getVerticalOffset();
    PDPage destination = document.getPage(pageIndex);
    int noOfLines =
        PdfUtility.splitString(title, SPACE_PER_TITLE_LINE, fonts.helvetica(), 12).length;
    PdfUtility.addLink(document, getPage(), destination, title, TITLE_XX_OFFSET, yyOffset,
        fonts.helvetica(), 12, SPACE_PER_TITLE_LINE, noOfLines);
    if (date.isPresent()) {
      PdfUtility.addText(document, getPage(), DATE_FORMAT.format(date.get()), DATE_XX_OFFSET,
          yyOffset - 3, fonts.helvetica(), 12, SPACE_PER_LINE);
    }
    PdfUtility.addText(document, getPage(), String.valueOf(pageIndex + 1), PAGE_XX_OFFSET,
        yyOffset - 3, fonts.helvetica(), 12, SPACE_PER_LINE);
    numLinesAdded += noOfLines;
    endOfFolder = false;
  }

  void addFolder(String title, int pageIndex) throws IOException {
    PDPage destination = document.getPage(pageIndex);
    float yyOffset = getVerticalOffset() + PdfUtility.LINE_HEIGHT;
    int noOfLines = PdfUtility.splitString(title, SPACE_PER_TITLE_LINE, fonts.helveticaBold(),
        FOLDER_FONT_SIZE).length;
    PdfUtility.addLink(document, getPage(), destination, title, TITLE_XX_OFFSET, yyOffset,
        fonts.helveticaBold(), FOLDER_FONT_SIZE, SPACE_PER_TITLE_LINE, noOfLines);
    numLinesAdded += noOfLines + 2;
    endOfFolder = false;
  }

  void setEndOfFolder(boolean value) {
    endOfFolder = value;
  }

  PDPage getPage() {
    int pageIndex = numLinesAdded / NUM_LINES_PER_PAGE;
    return pages.get(Math.min(pageIndex, pages.size() - 1));
  }

  int pageCount() {
    return pages.size();
  }

  static int estimatePages(AssemblyRequest request) {
    PdfFonts fonts = new PdfFonts();
    int lines = initialLines(request.description().orElse(""), fonts);
    lines = countLines(request.items(), request.presentation().sectionCoverSheets(), lines,
        new boolean[] {false}, new int[] {0}, fonts);
    return Math.max(1, (int) Math.ceil((double) lines / NUM_LINES_PER_PAGE));
  }

  static boolean hasRenderableItems(AssemblyNode node) {
    if (node instanceof AssemblyItem) {
      return true;
    }
    return ((AssemblyFolder) node).children().stream().anyMatch(TocRenderer::hasRenderableItems);
  }

  static String drawnItemTitle(String title, int ordinal) {
    return PdfUtility.isRenderableTitle(title) ? title : "Document " + ordinal;
  }

  private static int countLines(List<AssemblyNode> nodes, boolean sectionCoverSheets, int lines,
      boolean[] endOfFolder, int[] itemOrdinal, PdfFonts fonts) {
    for (AssemblyNode node : nodes) {
      if (node instanceof AssemblyFolder folder) {
        if (!hasRenderableItems(folder)) {
          continue;
        }
        if (sectionCoverSheets) {
          lines += PdfUtility.splitString(folder.title(), SPACE_PER_TITLE_LINE,
              fonts.helveticaBold(), FOLDER_FONT_SIZE).length + 2;
          endOfFolder[0] = false;
        }
        lines = countLines(folder.children(), sectionCoverSheets, lines, endOfFolder,
            itemOrdinal, fonts);
        endOfFolder[0] = true;
      } else {
        if (endOfFolder[0]) {
          lines += 1;
          endOfFolder[0] = false;
        }
        itemOrdinal[0]++;
        String drawnTitle = drawnItemTitle(node.title(), itemOrdinal[0]);
        lines += PdfUtility.splitString(drawnTitle, SPACE_PER_TITLE_LINE, fonts.helvetica(),
            12).length;
      }
    }
    return lines;
  }

  private static int initialLines(String description, PdfFonts fonts) {
    int descriptionLines =
        PdfUtility.splitString(description, SPACE_PER_LINE, fonts.helvetica(), 12).length;
    int indexVerticalOffset = Math.max(descriptionLines * 20 + 70, 90);
    int headerVerticalOffset = indexVerticalOffset + 30;
    return (int) ((headerVerticalOffset - TOP_MARGIN_OFFSET) / 20) + 2;
  }

  private void addSpaceAfterFolder() {
    if (endOfFolder) {
      numLinesAdded += 1;
      endOfFolder = false;
    }
  }

  private float getVerticalOffset() {
    return TOP_MARGIN_OFFSET + ((numLinesAdded % NUM_LINES_PER_PAGE) * PdfUtility.LINE_HEIGHT);
  }
}
