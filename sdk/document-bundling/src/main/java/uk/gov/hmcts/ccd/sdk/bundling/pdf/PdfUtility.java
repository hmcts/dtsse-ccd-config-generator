package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.ccd.sdk.bundling.api.PageNumbers;

/**
 * Low-level text, link and pagination drawing helpers, ported from {@code em-stitching-api}'s
 * {@code PDFUtility}. All vertical offsets are measured from the top of the page's visible
 * area.
 *
 * <p>Positions are computed against the page's <b>crop box</b> (which PDFBox defaults to the
 * media box when absent): on scanned evidence whose crop box is smaller than its media box, a
 * stamp positioned against the media box — as the current service does — can land outside the
 * visible area, silently hiding a legally required marking. For ordinary pages the two boxes
 * coincide and the output is identical to the service's.
 */
final class PdfUtility {

  static final int LINE_HEIGHT = 18;

  private static final Logger log = LoggerFactory.getLogger(PdfUtility.class);
  private static final int EDGE_OFFSET = 20;
  private static final int RIGHT_SIDE_OFFSET = 40;

  private PdfUtility() {
  }

  static void addCenterText(PDDocument document, PDPage page, String text, int yyOffset,
      PDType1Font font, float fontSize) throws IOException {
    if (text == null) {
      return;
    }
    try (PDPageContentStream contentStream =
        new PDPageContentStream(document, page, AppendMode.APPEND, true)) {
      PDRectangle box = page.getCropBox();
      final float stringWidth = getStringWidth(text, font, fontSize);
      final float titleHeight =
          font.getFontDescriptor().getFontBoundingBox().getHeight() / 1000 * fontSize;
      final float pageWidth = box.getWidth();
      float positionX = calculateCentrePositionX(pageWidth, stringWidth);
      writeText(contentStream, text, box.getLowerLeftX() + positionX,
          box.getUpperRightY() - yyOffset - titleHeight, font, fontSize,
          (int) (pageWidth - positionX * 2));
    }
  }

  static void addText(PDDocument document, PDPage page, String text, float xxOffset,
      float yyOffset, PDType1Font font, float fontSize, int lineWidth) throws IOException {
    if (text == null) {
      return;
    }
    try (PDPageContentStream stream =
        new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
      PDRectangle box = page.getCropBox();
      final float baseline = box.getUpperRightY() - yyOffset;
      writeText(stream, sanitizeText(text), box.getLowerLeftX() + xxOffset, baseline, font,
          fontSize, lineWidth);
    }
  }

  /**
   * Stamps one page with its printed page number. The label and position come from the approved
   * {@link PageNumbers} preset; the number printed is the page's absolute 1-based position in the
   * bundle, matching the current stitching service.
   *
   * @param document the merged document
   * @param preset the approved page-number preset; never {@link PageNumbers#NONE}
   * @param pageIndex the 0-based page index to stamp
   * @param totalPages the bundle's total page count, used by the {@code N of M} variants
   * @param font the bold stamping font of this assembly
   * @throws IOException if drawing fails
   */
  static void addPageNumber(PDDocument document, PageNumbers preset, int pageIndex,
      int totalPages, PDType1Font font) throws IOException {
    PDPage page = document.getPage(pageIndex);
    PDRectangle box = page.getCropBox();
    float pageWidth = box.getWidth();
    float pageHeight = box.getHeight();
    float xxOffset = switch (preset) {
      case BOTTOM_CENTRE_N, BOTTOM_CENTRE_N_OF_M -> pageWidth / 2;
      case BOTTOM_RIGHT_N, BOTTOM_RIGHT_N_OF_M, TOP_RIGHT_N, TOP_RIGHT_N_OF_M ->
          pageWidth - RIGHT_SIDE_OFFSET;
      case NONE -> throw new IllegalArgumentException("No location for PageNumbers.NONE");
    };
    float yyOffset = switch (preset) {
      case BOTTOM_CENTRE_N, BOTTOM_CENTRE_N_OF_M, BOTTOM_RIGHT_N, BOTTOM_RIGHT_N_OF_M ->
          pageHeight - EDGE_OFFSET;
      case TOP_RIGHT_N, TOP_RIGHT_N_OF_M -> EDGE_OFFSET;
      case NONE -> throw new IllegalArgumentException("No location for PageNumbers.NONE");
    };
    String label = switch (preset) {
      case BOTTOM_CENTRE_N, BOTTOM_RIGHT_N, TOP_RIGHT_N -> String.valueOf(pageIndex + 1);
      case BOTTOM_CENTRE_N_OF_M, BOTTOM_RIGHT_N_OF_M, TOP_RIGHT_N_OF_M ->
          (pageIndex + 1) + " of " + totalPages;
      case NONE -> throw new IllegalArgumentException("No label for PageNumbers.NONE");
    };
    addText(document, page, label, xxOffset, yyOffset, font, 13, lineWidthFor(page));
  }

  static float getStringWidth(String string, PDType1Font font, float fontSize) {
    try {
      // The text must be sanitised: getStringWidth() rejects characters outside WinAnsi.
      return font.getStringWidth(sanitizeText(string)) / 1000 * fontSize;
    } catch (IOException e) {
      log.info("Error getting string width information");
      return 0;
    }
  }

  /**
   * Draws {@code text} and a borderless link annotation over it that jumps to another page of the
   * same document.
   *
   * @param document the document
   * @param from the page carrying the link
   * @param to the destination page
   * @param text the link text
   * @param xxOffset the left offset of the text
   * @param yyOffset the top offset of the text
   * @param font the font
   * @param fontSize the font size
   * @param lineWidth the wrapping width in points
   * @param noOfLines the number of wrapped lines the link rectangle must cover
   * @throws IOException if drawing fails
   */
  static void addLink(PDDocument document, PDPage from, PDPage to, String text, float xxOffset,
      float yyOffset, PDType1Font font, float fontSize, int lineWidth, int noOfLines)
      throws IOException {
    PDAnnotationLink link = generateLink(to, from, xxOffset, yyOffset, noOfLines);
    removeLinkBorder(link);
    addText(document, from, text, xxOffset, yyOffset, font, fontSize, lineWidth);
  }

  static void addRightLink(PDDocument document, PDPage from, PDPage to, String text,
      float yyOffset, PDType1Font font, float fontSize) throws IOException {
    final float pageWidth = from.getCropBox().getWidth();
    final float stringWidth = getStringWidth(text, font, fontSize);
    final float xxOffset = pageWidth - stringWidth - 53;
    addLink(document, from, to, text, xxOffset, yyOffset, font, fontSize, lineWidthFor(from), 1);
  }

  /**
   * Draws {@code text} and a borderless link annotation over it that opens an absolute URI.
   *
   * @param document the document
   * @param page the page carrying the link
   * @param uri the absolute URI the link opens
   * @param text the link text
   * @param xxOffset the left offset of the text
   * @param yyOffset the top offset of the text
   * @param font the font
   * @param fontSize the font size
   * @param lineWidth the wrapping width in points
   * @throws IOException if drawing fails
   */
  static void addUriLink(PDDocument document, PDPage page, String uri, String text,
      float xxOffset, float yyOffset, PDType1Font font, float fontSize, int lineWidth)
      throws IOException {
    int noOfLines = Math.max(splitString(text, lineWidth, font, fontSize).length, 1);
    final PDActionURI action = new PDActionURI();
    action.setURI(uri);
    final PDAnnotationLink link = new PDAnnotationLink();
    link.setAction(action);
    link.setRectangle(linkRectangle(page, xxOffset, yyOffset, noOfLines));
    removeLinkBorder(link);
    page.getAnnotations().add(link);
    addText(document, page, text, xxOffset, yyOffset, font, fontSize, lineWidth);
  }

  static String sanitizeText(String rawString) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < rawString.length(); i++) {
      if (WinAnsiEncoding.INSTANCE.contains(rawString.charAt(i))) {
        sb.append(rawString.charAt(i));
      }
    }
    return sb.toString();
  }

  /**
   * Whether a title survives {@link #sanitizeText} with any visible character left. A title
   * that does not would render as a blank row in the contents and on cover sheets; the
   * assembler substitutes a deterministic fallback and warns instead.
   *
   * @param title the title to test
   * @return whether any drawable character remains after sanitisation
   */
  static boolean isRenderableTitle(String title) {
    return !sanitizeText(title).isBlank();
  }

  static String[] splitString(String text, int lineWidth, PDType1Font font, float fontSize) {
    if (text == null || text.isEmpty()) {
      return new String[0];
    }
    String[] words = text.split(" ");
    List<String> lines = new ArrayList<>();
    StringBuilder currentLine = new StringBuilder();
    float currentLineWidth = 0;
    for (String word : words) {
      try {
        float wordWidth = getStringWidth(word, font, fontSize);
        if (currentLineWidth + wordWidth <= lineWidth) {
          currentLineWidth = appendWord(currentLine, currentLineWidth, word, wordWidth);
          continue;
        }
        processLine(lines, currentLine);
        currentLine.setLength(0);
        currentLineWidth = appendWord(currentLine, 0, word, wordWidth);
      } catch (IllegalArgumentException e) {
        log.info("actual word :{} and text is :{} ", word, text);
      }
    }
    processLine(lines, currentLine);
    return lines.toArray(new String[0]);
  }

  static int lineWidthFor(PDPage page) {
    return (int) page.getCropBox().getWidth();
  }

  private static PDAnnotationLink generateLink(PDPage to, PDPage from, float xxOffset,
      float yyOffset, int noOfLines) throws IOException {
    final PDPageXYZDestination destination = new PDPageXYZDestination();
    destination.setPage(to);
    final PDActionGoTo action = new PDActionGoTo();
    action.setDestination(destination);
    final PDAnnotationLink link = new PDAnnotationLink();
    link.setAction(action);
    link.setDestination(destination);
    link.setRectangle(linkRectangle(from, xxOffset, yyOffset, noOfLines));
    from.getAnnotations().add(link);
    return link;
  }

  private static PDRectangle linkRectangle(PDPage page, float xxOffset, float yyOffset,
      int noOfLines) {
    PDRectangle box = page.getCropBox();
    final float pageWidth = box.getWidth();
    int height = LINE_HEIGHT * noOfLines;
    return new PDRectangle(
        box.getLowerLeftX() + xxOffset,
        box.getUpperRightY() - yyOffset - height + LINE_HEIGHT,
        pageWidth - xxOffset - 40,
        height);
  }

  private static void removeLinkBorder(PDAnnotationLink link) {
    PDBorderStyleDictionary borderLine = new PDBorderStyleDictionary();
    borderLine.setStyle(PDBorderStyleDictionary.STYLE_UNDERLINE);
    borderLine.setWidth(0);
    link.setBorderStyle(borderLine);
  }

  private static void writeText(PDPageContentStream contentStream, String text, float positionX,
      float positionY, PDType1Font font, float fontSize, int lineWidth) throws IOException {
    String[] lines = splitString(text, lineWidth, font, fontSize);
    for (String line : lines) {
      contentStream.beginText();
      contentStream.setFont(font, fontSize);
      contentStream.newLineAtOffset(positionX, positionY);
      contentStream.showText(sanitizeText(line));
      contentStream.endText();
      positionY = positionY - LINE_HEIGHT;
    }
    contentStream.setLineWidth((float) 0.25);
  }

  private static void processLine(List<String> lines, StringBuilder currentLine) {
    if (!currentLine.isEmpty()) {
      currentLine.setLength(currentLine.length() - 1);
      lines.add(currentLine.toString());
    }
  }

  private static float appendWord(StringBuilder currentLine, float currentLineWidth, String word,
      float wordWidth) {
    currentLine.append(word);
    currentLineWidth += wordWidth;
    currentLine.append(" ");
    currentLineWidth++;
    return currentLineWidth;
  }

  private static float calculateCentrePositionX(float pageWidth, float stringWidth) {
    float temp = stringWidth - pageWidth;
    if (temp > pageWidth) {
      return calculateCentrePositionX(pageWidth, temp);
    }
    return Math.abs(temp) / 2;
  }
}
