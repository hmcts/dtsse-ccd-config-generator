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

/**
 * Renders the clickable table of contents, ported from {@code em-stitching-api}'s
 * {@code TableOfContents}: the index pages are inserted up front (their count computed from the
 * same line accounting the renderer itself uses), every document entry is a link back into the
 * merged pages, folder entries are bold with a blank line before and after, an extra blank line
 * follows the end of each folder, titles wrap over multiple lines at the service's exact wrap
 * width (400pt), and entries flow across multiple index pages.
 *
 * <p>Two deliberate divergences from the current service: each entry shows the item's supplied
 * date and its 1-based start page (the service showed a page range or total-page count, chosen by
 * a request enum the SDK's presentation model does not expose), and source-outline "subtitle"
 * lines are not printed in the contents (source outlines are preserved as bookmarks instead).
 * The date and start-page columns sit to the right of the title column's 450pt extent, so they
 * never overlap a full-width title line.
 *
 * <p>A title with no WinAnsi-drawable characters at all would render as a blank contents row; the
 * renderer substitutes the deterministic fallback from {@link #drawnItemTitle} instead (the
 * assembler emits the warning). A partially non-WinAnsi title is drawn with the offending
 * characters dropped, exactly as the current service does — lossy, but with the full title still
 * present in the bookmark.
 */
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

  /**
   * Adds the index pages to the document and draws the heading: the optional description, the
   * centred "Index Page" title, and the date and page column headers.
   *
   * @param document the merged document, which must not yet contain content pages
   * @param request the assembly request
   * @param fonts the fonts of this assembly
   * @throws IOException if drawing fails
   */
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

  /**
   * Adds one document entry: the wrapped title as a link to the document's first page, the
   * supplied date, and the 1-based start page.
   *
   * @param title the drawn document title (already fallback-substituted when unrenderable)
   * @param date the supplied document date, when present
   * @param pageIndex the 0-based index of the document's first page, which must already exist
   * @throws IOException if drawing fails
   */
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

  /**
   * Adds one folder entry: a blank line, the wrapped bold title as a link to the folder's cover
   * sheet, and another blank line.
   *
   * @param title the folder title
   * @param pageIndex the 0-based index of the folder cover sheet, which must already exist
   * @throws IOException if drawing fails
   */
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

  /**
   * Marks that a folder has just ended, so the next entry is preceded by a blank line.
   *
   * @param value whether the end of a folder was just rendered
   */
  void setEndOfFolder(boolean value) {
    endOfFolder = value;
  }

  /**
   * The index page currently being written.
   *
   * @return the current index page
   */
  PDPage getPage() {
    int pageIndex = numLinesAdded / NUM_LINES_PER_PAGE;
    return pages.get(Math.min(pageIndex, pages.size() - 1));
  }

  /**
   * The number of index pages inserted.
   *
   * @return the index page count
   */
  int pageCount() {
    return pages.size();
  }

  /**
   * Computes how many index pages the request needs, by simulating the exact line accounting the
   * renderer performs during assembly, including the fallback titles substituted for
   * unrenderable ones.
   *
   * @param request the assembly request
   * @return the number of index pages, at least one
   */
  static int estimatePages(AssemblyRequest request) {
    PdfFonts fonts = new PdfFonts();
    int lines = initialLines(request.description().orElse(""), fonts);
    lines = countLines(request.items(), request.presentation().sectionCoverSheets(), lines,
        new boolean[] {false}, new int[] {0}, fonts);
    return Math.max(1, (int) Math.ceil((double) lines / NUM_LINES_PER_PAGE));
  }

  /**
   * Whether a node contains at least one renderable item; folders without any are skipped
   * entirely by the assembler and the estimate alike.
   *
   * @param node the node to inspect
   * @return whether the node renders anything
   */
  static boolean hasRenderableItems(AssemblyNode node) {
    if (node instanceof AssemblyItem) {
      return true;
    }
    return ((AssemblyFolder) node).children().stream().anyMatch(TocRenderer::hasRenderableItems);
  }

  /**
   * The title actually drawn for an item in the contents, on cover sheets and on generated
   * pages: the item's own title, or the deterministic fallback {@code "Document <n>"} (by
   * 1-based render order) when sanitisation would leave nothing visible. Bookmarks always keep
   * the original title.
   *
   * @param title the item's title
   * @param ordinal the item's 1-based position in render order
   * @return the drawn title
   */
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
