package uk.gov.hmcts.ccd.sdk.bundling.testsupport;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

public final class PdfSemantics {
  private static final int MAX_STAMP_DIGITS = 4;

  private static final float STAMP_EDGE_BAND = 60f;

  private PdfSemantics() {
  }

  public static Map<String, Object> extract(Path pdf) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      Map<String, Object> facts = new LinkedHashMap<>();
      facts.put("pageCount", document.getNumberOfPages());
      facts.put("pageLabels", extractPageLabels(document));
      facts.put("pages", extractPages(document));
      facts.put("outline", extractOutline(document));
      facts.put("links", extractLinks(document));
      return facts;
    }
  }

  public static String toJson(Map<String, Object> facts) {
    StringBuilder out = new StringBuilder("{\n");
    int remaining = facts.size();
    for (Map.Entry<String, Object> field : facts.entrySet()) {
      out.append("  \"").append(field.getKey()).append("\" : ");
      if (!(field.getValue() instanceof List<?> items) || items.isEmpty()) {
        out.append(oneLine(field.getValue()));
      } else {
        out.append("[\n");
        for (int i = 0; i < items.size(); i++) {
          if ("outline".equals(field.getKey())) {
            appendOutlineItem(out, asObject(items.get(i)), "    ");
          } else {
            out.append("    ").append(oneLine(items.get(i)));
          }
          out.append(i < items.size() - 1 ? ",\n" : "\n");
        }
        out.append("  ]");
      }
      out.append(--remaining > 0 ? ",\n" : "\n");
    }
    return out.append("}\n").toString();
  }

  public static Map<String, Object> readFacts(Path factsJson) throws IOException {
    return asObject(new JsonReader(Files.readString(factsJson)).read());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asObject(Object node) {
    return (Map<String, Object>) node;
  }

  private static void appendOutlineItem(StringBuilder out, Map<String, Object> item,
      String indent) {
    out.append(indent).append("{ ");
    int remaining = item.size();
    for (Map.Entry<String, Object> field : item.entrySet()) {
      out.append("\"").append(field.getKey()).append("\" : ");
      if (!"children".equals(field.getKey()) || !(field.getValue() instanceof List<?> children)
          || children.isEmpty()) {
        out.append(oneLine(field.getValue()));
      } else {
        out.append("[\n");
        for (int i = 0; i < children.size(); i++) {
          appendOutlineItem(out, asObject(children.get(i)), indent + "  ");
          out.append(i < children.size() - 1 ? ",\n" : "\n");
        }
        out.append(indent).append("]");
      }
      out.append(--remaining > 0 ? ", " : " }");
    }
  }

  private static String oneLine(Object node) {
    if (node instanceof Map<?, ?> object) {
      if (object.isEmpty()) {
        return "{ }";
      }
      StringBuilder out = new StringBuilder("{ ");
      int remaining = object.size();
      for (Map.Entry<?, ?> field : object.entrySet()) {
        out.append("\"").append(field.getKey()).append("\" : ").append(oneLine(field.getValue()))
            .append(--remaining > 0 ? ", " : " }");
      }
      return out.toString();
    }
    if (node instanceof List<?> items) {
      if (items.isEmpty()) {
        return "[ ]";
      }
      StringBuilder out = new StringBuilder("[ ");
      for (int i = 0; i < items.size(); i++) {
        out.append(oneLine(items.get(i))).append(i < items.size() - 1 ? ", " : " ]");
      }
      return out.toString();
    }
    if (node instanceof String text) {
      StringBuilder out = new StringBuilder("\"");
      for (char c : text.toCharArray()) {
        switch (c) {
          case '"' -> out.append("\\\"");
          case '\\' -> out.append("\\\\");
          case '\n' -> out.append("\\n");
          case '\r' -> out.append("\\r");
          case '\t' -> out.append("\\t");
          default -> out.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
        }
      }
      return out.append('"').toString();
    }
    return String.valueOf(node);
  }

  private static final class JsonReader {
    private final String text;
    private int pos;

    private JsonReader(String text) {
      this.text = text;
    }

    private Object read() {
      Object value = readValue();
      skipWhitespace();
      if (pos != text.length()) {
        throw new IllegalArgumentException("Trailing content at " + pos);
      }
      return value;
    }

    private Object readValue() {
      skipWhitespace();
      char c = text.charAt(pos);
      if (c == '{') {
        return readObject();
      }
      if (c == '[') {
        return readArray();
      }
      if (c == '"') {
        return readString();
      }
      if (text.startsWith("null", pos)) {
        pos += 4;
        return null;
      }
      if (text.startsWith("true", pos)) {
        pos += 4;
        return Boolean.TRUE;
      }
      if (text.startsWith("false", pos)) {
        pos += 5;
        return Boolean.FALSE;
      }
      int start = pos;
      while (pos < text.length() && "-+.eE0123456789".indexOf(text.charAt(pos)) >= 0) {
        pos++;
      }
      long number = Long.parseLong(text.substring(start, pos));
      return number == (int) number ? (Object) (int) number : (Object) number;
    }

    private Map<String, Object> readObject() {
      Map<String, Object> object = new LinkedHashMap<>();
      pos++;
      skipWhitespace();
      if (text.charAt(pos) == '}') {
        pos++;
        return object;
      }
      while (true) {
        skipWhitespace();
        String key = readString();
        skipWhitespace();
        expect(':');
        object.put(key, readValue());
        skipWhitespace();
        if (text.charAt(pos) == '}') {
          pos++;
          return object;
        }
        expect(',');
      }
    }

    private List<Object> readArray() {
      List<Object> array = new ArrayList<>();
      pos++;
      skipWhitespace();
      if (text.charAt(pos) == ']') {
        pos++;
        return array;
      }
      while (true) {
        array.add(readValue());
        skipWhitespace();
        if (text.charAt(pos) == ']') {
          pos++;
          return array;
        }
        expect(',');
      }
    }

    private String readString() {
      expect('"');
      StringBuilder out = new StringBuilder();
      while (true) {
        char c = text.charAt(pos++);
        if (c == '"') {
          return out.toString();
        }
        if (c != '\\') {
          out.append(c);
          continue;
        }
        char escaped = text.charAt(pos++);
        switch (escaped) {
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          case 'b' -> out.append('\b');
          case 'f' -> out.append('\f');
          case 'u' -> {
            out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
            pos += 4;
          }
          default -> out.append(escaped); // \" \\ \/
        }
      }
    }

    private void expect(char c) {
      if (text.charAt(pos) != c) {
        throw new IllegalArgumentException("Expected '" + c + "' at " + pos);
      }
      pos++;
    }

    private void skipWhitespace() {
      while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
        pos++;
      }
    }
  }

  private static List<Object> extractPageLabels(PDDocument document) throws IOException {
    PDPageLabels labels = document.getDocumentCatalog().getPageLabels();
    if (labels == null) {
      return null;
    }
    return new ArrayList<>(List.of(labels.getLabelsByPageIndices()));
  }

  private static List<Object> extractPages(PDDocument document) throws IOException {
    List<Object> pages = new ArrayList<>();
    for (int i = 1; i <= document.getNumberOfPages(); i++) {
      PDPage pdPage = document.getPage(i - 1);
      List<Line> lines = collectLines(document, i);

      Map<String, Object> page = new LinkedHashMap<>();
      page.put("page", i);
      page.put("text", String.join("\n", lines.stream().map(Line::text).toList()));
      page.put("pageNumberStamps", extractStamps(lines, pdPage.getMediaBox()));
      page.put("images", extractImages(pdPage));
      pages.add(page);
    }
    return pages;
  }

  private static List<Object> extractStamps(List<Line> lines, PDRectangle mediaBox) {
    List<Object> stamps = new ArrayList<>();
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
      Map<String, Object> stamp = new LinkedHashMap<>();
      stamp.put("value", Integer.parseInt(text));
      stamp.put("x", Math.round(line.x()));
      stamp.put("y", Math.round(pdfY));
      stamps.add(stamp);
    }
    return stamps;
  }

  private record Line(String text, float x, float displayY) {
  }

  private static List<Line> collectLines(PDDocument document, int pageNumber) throws IOException {
    LineCollector collector = new LineCollector();
    collector.setStartPage(pageNumber);
    collector.setEndPage(pageNumber);
    collector.getText(document);
    return collector.lines;
  }

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

  private static List<Object> extractImages(PDPage page) throws IOException {
    List<Map<String, Object>> images = new ArrayList<>();
    collectImages(page.getResources(), images, new HashSet<>());
    images.sort(Comparator
        .comparing((Map<String, Object> n) -> (String) n.get("pixelSha256"))
        .thenComparingInt(n -> (Integer) n.get("width"))
        .thenComparingInt(n -> (Integer) n.get("height")));
    return new ArrayList<>(images);
  }

  private static void collectImages(PDResources resources, List<Map<String, Object>> out,
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
        Map<String, Object> entry = new LinkedHashMap<>();
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

  private static List<Object> extractOutline(PDDocument document) throws IOException {
    List<Object> outline = new ArrayList<>();
    PDOutlineNode root = document.getDocumentCatalog().getDocumentOutline();
    if (root != null) {
      appendOutlineChildren(document, root, outline);
    }
    return outline;
  }

  private static void appendOutlineChildren(PDDocument document, PDOutlineNode node,
      List<Object> out) throws IOException {
    for (PDOutlineItem item : node.children()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("title", item.getTitle());
      entry.put("bold", item.isBold());
      entry.put("targetPage", resolveTargetPage(document, item.getDestination(), item.getAction()));
      List<Object> children = new ArrayList<>();
      appendOutlineChildren(document, item, children);
      entry.put("children", children);
      out.add(entry);
    }
  }

  private static List<Object> extractLinks(PDDocument document) throws IOException {
    List<Object> links = new ArrayList<>();
    int pageIndex = 0;
    for (PDPage page : document.getPages()) {
      pageIndex++;
      for (PDAnnotation annotation : page.getAnnotations()) {
        if (!(annotation instanceof PDAnnotationLink link)) {
          continue;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("sourcePage", pageIndex);
        PDRectangle rect = link.getRectangle();
        if (rect != null) {
          entry.put("rect", new ArrayList<>(List.of(Math.round(rect.getLowerLeftX()),
              Math.round(rect.getLowerLeftY()), Math.round(rect.getUpperRightX()),
              Math.round(rect.getUpperRightY()))));
        }
        entry.put("targetPage", resolveTargetPage(document, link.getDestination(), link.getAction()));
        links.add(entry);
      }
    }
    return links;
  }

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
