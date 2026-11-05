package uk.gov.hmcts.ccd.sdk.conversion.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;

/**
 * A structural PDF/X-1a:2001 (ISO 15930-1) checker for tests.
 *
 * <p>There is no open-source PDF/X validator (veraPDF covers PDF/A and PDF/UA only), so this
 * encodes the standard's machine-checkable rules: PDF 1.3 file structure, no encryption, a file
 * identifier, the identification keys, a GTS_PDFX output intent, a trim or art box on every
 * page, CMYK/grey/spot colour only, no transparency, no transfer functions, embedded fonts, no
 * PostScript, JavaScript or embedded files, and no annotations on the printed area. It is not
 * a substitute for a commercial preflight; it is the regression net for the rules the converter
 * is responsible for.
 */
public final class PdfX1aChecker {
  private static final Set<String> ALLOWED_COLOUR_SPACES =
      Set.of("DeviceCMYK", "DeviceGray", "Separation", "DeviceN", "Indexed", "Pattern");
  private static final Set<String> ALLOWED_FILTERS = Set.of("FlateDecode", "DCTDecode",
      "CCITTFaxDecode", "RunLengthDecode", "ASCII85Decode", "ASCIIHexDecode", "LZWDecode");
  private static final Set<String> RGB_OPERATORS = Set.of("rg", "RG");

  private PdfX1aChecker() {
  }

  /** Every rule the file breaks, as readable sentences. Empty means conformant. */
  public static List<String> violations(Path pdf) throws IOException {
    List<String> violations = new ArrayList<>();
    String raw = new String(Files.readAllBytes(pdf), StandardCharsets.ISO_8859_1);
    if (!raw.startsWith("%PDF-1.3") && !raw.startsWith("%PDF-1.2")
        && !raw.startsWith("%PDF-1.1") && !raw.startsWith("%PDF-1.0")) {
      violations.add("header is " + raw.substring(0, Math.min(8, raw.length()))
          + ", PDF/X-1a:2001 requires PDF 1.3 or lower");
    }
    if (raw.contains("/ObjStm")) {
      violations.add("uses object streams (PDF 1.5)");
    }
    if (raw.matches("(?s).*/Type\\s*/XRef\\b.*")) {
      violations.add("uses a cross-reference stream (PDF 1.5)");
    }
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      String catalogVersion = document.getDocumentCatalog().getVersion();
      if (catalogVersion != null && catalogVersion.compareTo("1.3") > 0) {
        violations.add("catalog /Version " + catalogVersion + " overrides the header; PDF/X-1a:2001"
            + " requires PDF 1.3 or lower");
      }
      checkDocument(document, violations);
      for (int i = 0; i < document.getNumberOfPages(); i++) {
        checkPage(document.getPage(i), i + 1, violations);
      }
    }
    return violations;
  }

  private static void checkDocument(PDDocument document, List<String> violations) {
    if (document.isEncrypted()) {
      violations.add("is encrypted");
    }
    COSBase id = document.getDocument().getTrailer().getDictionaryObject(COSName.ID);
    if (!(id instanceof COSArray ids) || ids.size() != 2) {
      violations.add("has no trailer /ID");
    }
    COSDictionary info = document.getDocumentInformation().getCOSObject();
    requireString(info, "GTS_PDFXVersion", "PDF/X-1:2001", violations);
    requireString(info, "GTS_PDFXConformance", "PDF/X-1a:2001", violations);
    String title = info.getString(COSName.TITLE);
    if (title == null || title.isBlank()) {
      violations.add("has no document information /Title");
    }
    for (COSName key : List.of(COSName.CREATION_DATE, COSName.MOD_DATE)) {
      if (info.getDictionaryObject(key) == null) {
        violations.add("has no document information /" + key.getName());
      }
    }
    COSBase trapped = info.getDictionaryObject(COSName.TRAPPED);
    if (!(trapped instanceof COSName name)
        || !Set.of("True", "False").contains(name.getName())) {
      violations.add("/Trapped must be the name /True or /False, was " + trapped);
    }

    COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
    COSBase intents = catalog.getDictionaryObject(COSName.OUTPUT_INTENTS);
    int pdfxIntents = 0;
    if (intents instanceof COSArray array) {
      for (int i = 0; i < array.size(); i++) {
        if (array.getObject(i) instanceof COSDictionary intent
            && COSName.getPDFName("GTS_PDFX").equals(intent.getDictionaryObject(COSName.S))) {
          pdfxIntents++;
          String identifier = intent.getString("OutputConditionIdentifier");
          if (identifier == null || identifier.isBlank()) {
            violations.add("output intent has no OutputConditionIdentifier");
          }
          if (intent.getDictionaryObject("DestOutputProfile") == null
              && (intent.getString("RegistryName") == null
                  || intent.getString("RegistryName").isBlank())) {
            violations.add("output intent names no registered condition and embeds no profile");
          }
        }
      }
    }
    if (pdfxIntents != 1) {
      violations.add("needs exactly one GTS_PDFX output intent, has " + pdfxIntents);
    }
    if (catalog.getDictionaryObject(COSName.AA) != null) {
      violations.add("catalog has additional actions");
    }
    if (catalog.getDictionaryObject(COSName.NAMES) instanceof COSDictionary names) {
      if (names.getDictionaryObject(COSName.JAVA_SCRIPT) != null) {
        violations.add("contains JavaScript");
      }
      if (names.getDictionaryObject(COSName.EMBEDDED_FILES) != null) {
        violations.add("contains embedded files");
      }
    }
  }

  private static void checkPage(PDPage page, int number, List<String> violations)
      throws IOException {
    String where = "page " + number + ": ";
    COSDictionary dict = page.getCOSObject();
    boolean trim = dict.getDictionaryObject(COSName.TRIM_BOX) != null;
    boolean art = dict.getDictionaryObject(COSName.ART_BOX) != null;
    if (trim == art) {
      violations.add(where + "needs exactly one of TrimBox and ArtBox");
    }
    PDRectangle media = page.getMediaBox();
    PDRectangle trimBox = page.getTrimBox();
    if (!contains(media, trimBox)) {
      violations.add(where + "TrimBox is not inside the MediaBox");
    }
    if (dict.getDictionaryObject(COSName.BLEED_BOX) != null
        && !contains(page.getBleedBox(), trimBox)) {
      violations.add(where + "TrimBox is not inside the BleedBox");
    }
    checkGroup(dict, where, violations);
    if (dict.getDictionaryObject(COSName.AA) != null) {
      violations.add(where + "has additional actions");
    }
    for (PDAnnotation annotation : page.getAnnotations()) {
      if (!"PrinterMark".equals(annotation.getSubtype())
          && intersects(annotation.getRectangle(), trimBox)) {
        violations.add(where + annotation.getSubtype() + " annotation on the printed area");
      }
    }
    checkContent(page, page.getResources(), where, violations, new IdentityHashMap<>());
  }

  private static void checkContent(PDContentStream content, PDResources resources, String where,
      List<String> violations, Map<COSBase, Boolean> seen) throws IOException {
    if (resources != null) {
      checkResources(resources, where, violations, seen);
    }
    PDFStreamParser parser = new PDFStreamParser(content);
    List<Object> operands = new ArrayList<>();
    for (Object token = parser.parseNextToken(); token != null; token = parser.parseNextToken()) {
      if (!(token instanceof Operator operator)) {
        operands.add(token);
        continue;
      }
      String name = operator.getName();
      if (RGB_OPERATORS.contains(name)) {
        violations.add(where + "sets DeviceRGB colour with '" + name + "'");
      }
      if (("cs".equals(name) || "CS".equals(name)) && !operands.isEmpty()
          && operands.get(0) instanceof COSName space
          && space.getName().startsWith("Device") && !ALLOWED_COLOUR_SPACES.contains(
              space.getName())) {
        violations.add(where + "selects colour space " + space.getName());
      }
      if ("BI".equals(name) || "ID".equals(name)) {
        COSDictionary inline = operator.getImageParameters();
        if (inline != null) {
          COSBase space = inline.getDictionaryObject(COSName.CS, COSName.COLORSPACE);
          if (space instanceof COSName spaceName
              && Set.of("RGB", "DeviceRGB", "CalRGB").contains(spaceName.getName())) {
            violations.add(where + "inline image in " + spaceName.getName());
          }
        }
      }
      operands.clear();
    }
  }

  private static void checkResources(PDResources resources, String where,
      List<String> violations, Map<COSBase, Boolean> seen) throws IOException {
    if (seen.put(resources.getCOSObject(), Boolean.TRUE) != null) {
      return;
    }
    for (COSName name : resources.getColorSpaceNames()) {
      checkColourSpace(resources.getCOSObject().getCOSDictionary(COSName.COLORSPACE)
          .getDictionaryObject(name), where + "colour space " + name.getName(), violations);
    }
    for (COSName name : resources.getExtGStateNames()) {
      COSDictionary state = resources.getExtGState(name).getCOSObject();
      COSBase softMask = state.getDictionaryObject(COSName.SMASK);
      if (softMask != null && !COSName.NONE.equals(softMask)) {
        violations.add(where + "graphics state " + name.getName() + " has a soft mask");
      }
      for (COSName alpha : List.of(COSName.CA, COSName.CA_NS)) {
        if (state.getDictionaryObject(alpha) instanceof COSNumber value
            && value.floatValue() < 1f) {
          violations.add(where + "graphics state " + name.getName() + " is "
              + value.floatValue() + " opaque");
        }
      }
      COSBase blend = state.getDictionaryObject(COSName.BM);
      if (blend != null && !(blend instanceof COSName mode
          && Set.of("Normal", "Compatible").contains(mode.getName()))) {
        violations.add(where + "graphics state " + name.getName() + " uses blend mode " + blend);
      }
      if (state.getDictionaryObject(COSName.TR) != null
          || state.getDictionaryObject(COSName.getPDFName("TR2")) != null) {
        violations.add(where + "graphics state " + name.getName() + " has a transfer function");
      }
    }
    for (COSName name : resources.getFontNames()) {
      PDFont font = resources.getFont(name);
      if (!(font instanceof PDType3Font) && !font.isEmbedded()) {
        violations.add(where + "font " + font.getName() + " is not embedded");
      }
    }
    for (COSName name : resources.getXObjectNames()) {
      PDXObject xobject = resources.getXObject(name);
      COSStream stream = xobject.getCOSObject();
      if (COSName.PS.equals(stream.getDictionaryObject(COSName.SUBTYPE))) {
        violations.add(where + "PostScript XObject " + name.getName());
      } else if (xobject instanceof PDImageXObject image) {
        checkImage(image, where + "image " + name.getName(), violations);
      } else if (xobject instanceof PDFormXObject form) {
        checkGroup(stream, where + "form " + name.getName() + " ", violations);
        checkContent(form, form.getResources(), where, violations, seen);
      }
    }
  }

  private static void checkImage(PDImageXObject image, String where, List<String> violations) {
    COSStream stream = image.getCOSObject();
    if (stream.getDictionaryObject(COSName.SMASK) != null) {
      violations.add(where + " has a soft mask (transparency)");
    }
    if (stream.getDictionaryObject(COSName.getPDFName("SMaskInData")) instanceof COSNumber n
        && n.intValue() != 0) {
      violations.add(where + " has SMaskInData");
    }
    if (stream.getDictionaryObject(COSName.getPDFName("OPI")) != null) {
      violations.add(where + " has OPI");
    }
    if (stream.getDictionaryObject(COSName.getPDFName("Alternates")) != null) {
      violations.add(where + " has alternates");
    }
    if (image.getInterpolate()) {
      violations.add(where + " requests interpolation");
    }
    if (stream.getInt(COSName.BITS_PER_COMPONENT, 8) > 8) {
      violations.add(where + " has more than 8 bits per component");
    }
    COSBase filters = stream.getFilters();
    for (COSBase filter : filters instanceof COSArray array ? array.toList()
        : filters == null ? List.<COSBase>of() : List.of(filters)) {
      if (filter instanceof COSName name && !ALLOWED_FILTERS.contains(name.getName())) {
        violations.add(where + " uses filter " + name.getName());
      }
    }
    if (!image.isStencil()) {
      checkColourSpace(stream.getDictionaryObject(COSName.COLORSPACE, COSName.CS), where,
          violations);
    }
  }

  private static void checkColourSpace(COSBase space, String where, List<String> violations) {
    String family = space instanceof COSName name ? name.getName()
        : space instanceof COSArray array && array.getObject(0) instanceof COSName first
            ? first.getName() : String.valueOf(space);
    if (!ALLOWED_COLOUR_SPACES.contains(family)) {
      violations.add(where + " is in " + family + "; only CMYK, grey and spot are allowed");
    } else if ("Indexed".equals(family) && space instanceof COSArray array) {
      checkColourSpace(array.getObject(1), where + " (Indexed base)", violations);
    }
  }

  private static void checkGroup(COSDictionary dict, String where, List<String> violations) {
    if (dict.getDictionaryObject(COSName.GROUP) instanceof COSDictionary group
        && COSName.TRANSPARENCY.equals(group.getDictionaryObject(COSName.S))) {
      violations.add(where + "has a transparency group");
    }
  }

  private static void requireString(COSDictionary info, String key, String expected,
      List<String> violations) {
    COSBase value = info.getDictionaryObject(key);
    String actual = value instanceof COSString string ? string.getString() : null;
    if (!expected.equals(actual)) {
      violations.add("document information /" + key + " must be '" + expected + "', was "
          + actual);
    }
  }

  private static boolean contains(PDRectangle outer, PDRectangle inner) {
    float e = 0.01f;
    return inner.getLowerLeftX() >= outer.getLowerLeftX() - e
        && inner.getLowerLeftY() >= outer.getLowerLeftY() - e
        && inner.getUpperRightX() <= outer.getUpperRightX() + e
        && inner.getUpperRightY() <= outer.getUpperRightY() + e;
  }

  private static boolean intersects(PDRectangle a, PDRectangle b) {
    return a.getLowerLeftX() < b.getUpperRightX() && b.getLowerLeftX() < a.getUpperRightX()
        && a.getLowerLeftY() < b.getUpperRightY() && b.getLowerLeftY() < a.getUpperRightY();
  }
}
