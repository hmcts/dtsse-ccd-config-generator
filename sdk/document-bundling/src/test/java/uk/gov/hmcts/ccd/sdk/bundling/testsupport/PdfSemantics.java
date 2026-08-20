package uk.gov.hmcts.ccd.sdk.bundling.testsupport;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDPageLabels;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Extracts the semantic facts of a PDF into a stable JSON structure.
 *
 * <p>The structure is the one serialised by the bundling characterisation harness
 * ({@code scripts/bundling-characterisation}) into
 * {@code src/test/resources/characterisation/<scenario>/facts.json}, so regression tests can
 * compare a freshly assembled bundle against a committed golden with plain {@link JsonNode}
 * equality. Only producer-independent facts are captured:
 *
 * <ul>
 *   <li>{@code pageCount} — total number of pages;</li>
 *   <li>{@code pageLabels} — the catalog page-label dictionary rendered per page, or
 *       {@code null} when the document declares none;</li>
 *   <li>{@code pages[].text} — per-page extracted text, <b>position-sorted</b>
 *       ({@code PDFTextStripper.setSortByPosition(true)}) and <b>whitespace-normalised</b>
 *       (each line trimmed, internal whitespace runs collapsed to a single space, empty lines
 *       dropped, lines joined with {@code \n}), so two visually identical pages extract to the
 *       same text regardless of content-stream draw order or exact glyph spacing;</li>
 *   <li>{@code pages[].pageNumberStamps} — the visible page-number stamps em-stitching prints
 *       when pagination is on, each with its {@code value} and the rounded PDF-space
 *       {@code x}/{@code y} of the line start so a stamp printed in the wrong corner fails
 *       comparison. Heuristic, deliberately bounded: a line consisting solely of 1–4 digits
 *       whose baseline lies within 60pt of the top or bottom page edge (all six em-stitching
 *       {@code PaginationStyle} positions sit ~20pt from those edges; longer digit runs such
 *       as 16-digit CCD case references, and digits in body content, are never stamps);</li>
 *   <li>{@code pages[].images} — every image XObject reachable from the page's resources
 *       (recursing through form XObjects, as PDFBox's Overlay nests watermark images), with
 *       intrinsic {@code width}/{@code height} and a content hash: {@code px:}-prefixed
 *       SHA-256 over the decoded ARGB raster (producer-independent — survives recompression),
 *       falling back to {@code raw:}-prefixed SHA-256 of the encoded stream when the raster
 *       cannot be decoded. Entries are sorted by (hash, width, height) for determinism;</li>
 *   <li>{@code outline} — the full outline tree with per-item title, bold flag and resolved
 *       1-based target page ({@code null} when the item points nowhere);</li>
 *   <li>{@code links} — every link annotation with its source page, rounded rectangle and
 *       resolved 1-based target page.</li>
 * </ul>
 *
 * <p>Deliberately absent: bytes, object order, metadata, fonts and exact glyph positioning,
 * all of which differ legitimately between PDF producers.
 */
public final class PdfSemantics {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Maximum digits a printed page-number stamp may have (bundle ceiling is ~1,000 pages). */
  private static final int MAX_STAMP_DIGITS = 4;

  /** Stamps sit ~20pt from the top/bottom edge; anything deeper into the page is content. */
  private static final float STAMP_EDGE_BAND = 60f;

  private PdfSemantics() {
  }

  /** Extracts the semantic facts of the given PDF. */
  public static ObjectNode extract(Path pdf) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      ObjectNode facts = MAPPER.createObjectNode();
      facts.put("pageCount", document.getNumberOfPages());
      facts.set("pageLabels", extractPageLabels(document));
      facts.set("pages", extractPages(document));
      facts.set("outline", extractOutline(document));
      facts.set("links", extractLinks(document));
      return facts;
    }
  }

  /** Renders facts as deterministic, platform-independent pretty-printed JSON. */
  public static String toJson(JsonNode facts) throws IOException {
    DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
    DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
        .withObjectIndenter(indenter)
        .withArrayIndenter(indenter);
    return MAPPER.writer(printer).writeValueAsString(facts) + "\n";
  }

  /** Reads a committed facts.json back for comparison against {@link #extract(Path)}. */
  public static JsonNode readFacts(Path factsJson) throws IOException {
    return MAPPER.readTree(Files.readString(factsJson));
  }

  private static JsonNode extractPageLabels(PDDocument document) throws IOException {
    PDPageLabels labels = document.getDocumentCatalog().getPageLabels();
    if (labels == null) {
      return NullNode.getInstance();
    }
    ArrayNode node = MAPPER.createArrayNode();
    for (String label : labels.getLabelsByPageIndices()) {
      node.add(label);
    }
    return node;
  }

  private static ArrayNode extractPages(PDDocument document) throws IOException {
    ArrayNode pages = MAPPER.createArrayNode();
    for (int i = 1; i <= document.getNumberOfPages(); i++) {
      PDPage pdPage = document.getPage(i - 1);
      List<Line> lines = collectLines(document, i);

      ObjectNode page = MAPPER.createObjectNode();
      page.put("page", i);
      page.put("text", String.join("\n", lines.stream().map(Line::text).toList()));
      page.set("pageNumberStamps", extractStamps(lines, pdPage.getMediaBox()));
      page.set("images", extractImages(pdPage));
      pages.add(page);
    }
    return pages;
  }

  private static ArrayNode extractStamps(List<Line> lines, PDRectangle mediaBox) {
    ArrayNode stamps = MAPPER.createArrayNode();
    float height = mediaBox.getHeight();
    for (Line line : lines) {
      String text = line.text();
      if (text.isEmpty() || text.length() > MAX_STAMP_DIGITS
          || !text.chars().allMatch(Character::isDigit)) {
        continue;
      }
      float pdfY = height - line.displayY();
      boolean inStampBand = pdfY <= STAMP_EDGE_BAND || pdfY >= height - STAMP_EDGE_BAND;
      if (!inStampBand) {
        continue;
      }
      ObjectNode stamp = MAPPER.createObjectNode();
      stamp.put("value", Integer.parseInt(text));
      stamp.put("x", Math.round(line.x()));
      stamp.put("y", Math.round(pdfY));
      stamps.add(stamp);
    }
    return stamps;
  }

  /** A whitespace-normalised text line with the display coordinates of its first glyph. */
  private record Line(String text, float x, float displayY) {
  }

  private static List<Line> collectLines(PDDocument document, int pageNumber) throws IOException {
    LineCollector collector = new LineCollector();
    collector.setStartPage(pageNumber);
    collector.setEndPage(pageNumber);
    collector.getText(document);
    return collector.lines;
  }

  /** Position-sorted stripper that gathers whole normalised lines with their coordinates. */
  private static final class LineCollector extends PDFTextStripper {

    private final List<Line> lines = new ArrayList<>();
    private final StringBuilder current = new StringBuilder();
    private TextPosition firstPosition;

    private LineCollector() {
      setSortByPosition(true);
      setLineSeparator("\n");
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) {
      if (firstPosition == null && !textPositions.isEmpty()) {
        firstPosition = textPositions.get(0);
      }
      current.append(text);
    }

    @Override
    protected void writeWordSeparator() {
      current.append(' ');
    }

    @Override
    protected void writeLineSeparator() {
      flushLine();
    }

    @Override
    protected void endPage(PDPage page) throws IOException {
      flushLine();
      super.endPage(page);
    }

    private void flushLine() {
      String normalised = current.toString().trim().replaceAll("\\s+", " ");
      if (!normalised.isEmpty() && firstPosition != null) {
        lines.add(new Line(normalised, firstPosition.getX(), firstPosition.getY()));
      }
      current.setLength(0);
      firstPosition = null;
    }
  }

  private static ArrayNode extractImages(PDPage page) throws IOException {
    List<ObjectNode> images = new ArrayList<>();
    collectImages(page.getResources(), images, new HashSet<>());
    images.sort(Comparator
        .comparing((ObjectNode n) -> n.get("pixelSha256").textValue())
        .thenComparingInt(n -> n.get("width").intValue())
        .thenComparingInt(n -> n.get("height").intValue()));
    ArrayNode node = MAPPER.createArrayNode();
    images.forEach(node::add);
    return node;
  }

  private static void collectImages(PDResources resources, List<ObjectNode> out,
      Set<COSBase> visited) throws IOException {
    if (resources == null) {
      return;
    }
    for (COSName name : resources.getXObjectNames()) {
      PDXObject xobject;
      try {
        xobject = resources.getXObject(name);
      } catch (IOException e) {
        continue;
      }
      if (xobject instanceof PDImageXObject image) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("width", image.getWidth());
        entry.put("height", image.getHeight());
        entry.put("pixelSha256", imageHash(image));
        out.add(entry);
      } else if (xobject instanceof PDFormXObject form
          && visited.add(form.getCOSObject())) {
        collectImages(form.getResources(), out, visited);
      }
    }
  }

  private static String imageHash(PDImageXObject image) {
    try {
      return "px:" + rasterSha256(image.getImage());
    } catch (Exception decodeFailure) {
      try {
        return "raw:" + HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(image.getStream().toByteArray()));
      } catch (Exception rawFailure) {
        return "unhashable";
      }
    }
  }

  private static String rasterSha256(BufferedImage image) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    digest.update(ByteBuffer.allocate(8)
        .putInt(image.getWidth())
        .putInt(image.getHeight())
        .array());
    int[] row = new int[image.getWidth()];
    ByteBuffer buffer = ByteBuffer.allocate(row.length * 4);
    for (int y = 0; y < image.getHeight(); y++) {
      image.getRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
      buffer.clear();
      buffer.asIntBuffer().put(row);
      digest.update(buffer.array());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static ArrayNode extractOutline(PDDocument document) throws IOException {
    ArrayNode outline = MAPPER.createArrayNode();
    PDOutlineNode root = document.getDocumentCatalog().getDocumentOutline();
    if (root != null) {
      appendOutlineChildren(document, root, outline);
    }
    return outline;
  }

  private static void appendOutlineChildren(PDDocument document, PDOutlineNode node, ArrayNode out)
      throws IOException {
    for (PDOutlineItem item : node.children()) {
      ObjectNode entry = MAPPER.createObjectNode();
      entry.put("title", item.getTitle());
      entry.put("bold", item.isBold());
      Integer target = resolveTargetPage(document, item.getDestination(), item.getAction());
      if (target == null) {
        entry.set("targetPage", NullNode.getInstance());
      } else {
        entry.put("targetPage", target);
      }
      ArrayNode children = MAPPER.createArrayNode();
      appendOutlineChildren(document, item, children);
      entry.set("children", children);
      out.add(entry);
    }
  }

  private static ArrayNode extractLinks(PDDocument document) throws IOException {
    ArrayNode links = MAPPER.createArrayNode();
    int pageIndex = 0;
    for (PDPage page : document.getPages()) {
      pageIndex++;
      for (PDAnnotation annotation : page.getAnnotations()) {
        if (!(annotation instanceof PDAnnotationLink link)) {
          continue;
        }
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("sourcePage", pageIndex);
        PDRectangle rect = link.getRectangle();
        if (rect != null) {
          ArrayNode rectangle = MAPPER.createArrayNode();
          rectangle.add(Math.round(rect.getLowerLeftX()));
          rectangle.add(Math.round(rect.getLowerLeftY()));
          rectangle.add(Math.round(rect.getUpperRightX()));
          rectangle.add(Math.round(rect.getUpperRightY()));
          entry.set("rect", rectangle);
        }
        Integer target = resolveTargetPage(document, link.getDestination(), link.getAction());
        if (target == null) {
          entry.set("targetPage", NullNode.getInstance());
        } else {
          entry.put("targetPage", target);
        }
        links.add(entry);
      }
    }
    return links;
  }

  /**
   * Resolves an explicit destination or GoTo action to a 1-based page number, following
   * named destinations through the catalog, or {@code null} when the target is missing or
   * points at a page that is not part of the document (as em-stitching's TableOfContents
   * does for some outline subtitles).
   */
  private static Integer resolveTargetPage(PDDocument document, PDDestination destination,
      PDAction action) {
    try {
      PDDestination resolved = destination;
      if (resolved == null && action instanceof PDActionGoTo goTo) {
        resolved = goTo.getDestination();
      }
      if (resolved instanceof PDNamedDestination named) {
        resolved = document.getDocumentCatalog().findNamedDestinationPage(named);
      }
      if (resolved instanceof PDPageDestination pageDestination) {
        int index = pageDestination.retrievePageNumber();
        if (index < 0 && pageDestination.getPage() != null) {
          index = document.getPages().indexOf(pageDestination.getPage());
        }
        return index >= 0 ? index + 1 : null;
      }
      return null;
    } catch (IOException e) {
      return null;
    }
  }
}
