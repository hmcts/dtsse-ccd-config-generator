package uk.gov.hmcts.ccd.sdk.conversion.testsupport;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.contentstream.operator.state.SetMatrix;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;

/**
 * What a printer sees in a PDF, in print units: page and trim sizes in millimetres, and for every
 * placed image its pixel size, colour space, encoding, distance from each edge in millimetres
 * and effective resolution. This is the semantic golden for print-ready output; it says nothing
 * about byte layout, dates or IDs, so it is stable across runs and platforms.
 */
public final class PrintFacts {
  private static final double MM_PER_POINT = 25.4 / 72;

  private PrintFacts() {
  }

  /** One image drawn on a page. */
  public record PlacedImage(int pixelWidth, int pixelHeight, String colourSpace,
      int bitsPerComponent, String filter, boolean softMask, double leftMm, double bottomMm,
      double rightMm, double topMm, double dpiX, double dpiY) {
  }

  /** One page. */
  public record Page(double widthMm, double heightMm, String trimBox, boolean hasText,
      List<PlacedImage> images) {
  }

  /** Extracts the facts. */
  public static Map<String, Object> extract(Path pdf) throws IOException {
    Map<String, Object> facts = new LinkedHashMap<>();
    String header = new String(Files.readAllBytes(pdf), 0, 8, StandardCharsets.ISO_8859_1);
    facts.put("header", header);
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      COSDictionary info = document.getDocumentInformation().getCOSObject();
      facts.put("pdfxVersion", info.getString("GTS_PDFXVersion"));
      facts.put("pdfxConformance", info.getString("GTS_PDFXConformance"));
      facts.put("outputCondition", outputCondition(document));
      facts.put("pageCount", document.getNumberOfPages());
      List<String> pages = new ArrayList<>();
      for (Page page : pages(document)) {
        pages.add(describe(page));
      }
      facts.put("pages", pages);
    }
    return facts;
  }

  /** The pages, with their placed images. */
  public static List<Page> pages(PDDocument document) throws IOException {
    List<Page> pages = new ArrayList<>();
    PDFTextStripper stripper = new PDFTextStripper();
    for (int i = 0; i < document.getNumberOfPages(); i++) {
      PDPage page = document.getPage(i);
      PDRectangle media = page.getMediaBox();
      ImageLocator locator = new ImageLocator(media);
      locator.processPage(page);
      stripper.setStartPage(i + 1);
      stripper.setEndPage(i + 1);
      boolean text = !stripper.getText(document).isBlank();
      String trim = page.getCOSObject().getDictionaryObject(COSName.TRIM_BOX) == null ? "none"
          : box(page.getTrimBox());
      pages.add(new Page(mm(media.getWidth()), mm(media.getHeight()), trim, text,
          locator.images));
    }
    return pages;
  }

  /** The output rendered at {@code dpi} in greyscale, one image per page. */
  public static List<BufferedImage> render(Path pdf, float dpi) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      PDFRenderer renderer = new PDFRenderer(document);
      List<BufferedImage> pages = new ArrayList<>();
      for (int i = 0; i < document.getNumberOfPages(); i++) {
        pages.add(renderer.renderImageWithDPI(i, dpi, ImageType.GRAY));
      }
      return pages;
    }
  }

  private static String describe(Page page) {
    StringBuilder out = new StringBuilder();
    out.append(String.format(Locale.ROOT, "%.1f x %.1f mm, trim %s, text %s", page.widthMm(),
        page.heightMm(), page.trimBox(), page.hasText()));
    for (PlacedImage image : page.images()) {
      out.append(String.format(Locale.ROOT,
          "; image %d x %d px %s %d-bit %s%s, edges L%.1f B%.1f R%.1f T%.1f mm, %.0f x %.0f dpi",
          image.pixelWidth(), image.pixelHeight(), image.colourSpace(),
          image.bitsPerComponent(), image.filter(), image.softMask() ? " +softmask" : "",
          image.leftMm(), image.bottomMm(), image.rightMm(), image.topMm(), image.dpiX(),
          image.dpiY()));
    }
    return out.toString();
  }

  private static String outputCondition(PDDocument document) {
    COSBase intents = document.getDocumentCatalog().getCOSObject()
        .getDictionaryObject(COSName.OUTPUT_INTENTS);
    if (!(intents instanceof COSArray array) || array.size() == 0) {
      return null;
    }
    COSDictionary intent = (COSDictionary) array.getObject(0);
    return intent.getNameAsString(COSName.S) + " " + intent.getString("OutputConditionIdentifier")
        + " @ " + intent.getString("RegistryName");
  }

  private static String box(PDRectangle box) {
    return String.format(Locale.ROOT, "%.1f,%.1f,%.1f,%.1f", mm(box.getLowerLeftX()),
        mm(box.getLowerLeftY()), mm(box.getUpperRightX()), mm(box.getUpperRightY()));
  }

  private static double mm(double points) {
    return Math.round(points * MM_PER_POINT * 10) / 10.0;
  }

  /** Records where each image XObject is drawn, from the CTM at its {@code Do}. */
  private static final class ImageLocator extends PDFStreamEngine {
    private final PDRectangle media;
    private final List<PlacedImage> images = new ArrayList<>();

    ImageLocator(PDRectangle media) {
      this.media = media;
      addOperator(new Concatenate(this));
      addOperator(new SetMatrix(this));
      addOperator(new Save(this));
      addOperator(new Restore(this));
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
      if ("Do".equals(operator.getName()) && !operands.isEmpty()
          && operands.get(0) instanceof COSName name) {
        PDXObject xobject = getResources().getXObject(name);
        if (xobject instanceof PDImageXObject image) {
          Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
          float width = ctm.getScalingFactorX();
          float height = ctm.getScalingFactorY();
          float x = ctm.getTranslateX();
          float y = ctm.getTranslateY();
          COSBase filter = image.getCOSObject().getFilters();
          images.add(new PlacedImage(image.getWidth(), image.getHeight(),
              image.getColorSpace().getName(), image.getBitsPerComponent(),
              filter instanceof COSName f ? f.getName() : String.valueOf(filter),
              image.getCOSObject().getDictionaryObject(COSName.SMASK) != null,
              mm(x - media.getLowerLeftX()), mm(y - media.getLowerLeftY()),
              mm(media.getUpperRightX() - x - width), mm(media.getUpperRightY() - y - height),
              Math.round(image.getWidth() / (width / 72f) * 10) / 10.0,
              Math.round(image.getHeight() / (height / 72f) * 10) / 10.0));
        }
        return;
      }
      super.processOperator(operator, operands);
    }
  }
}
