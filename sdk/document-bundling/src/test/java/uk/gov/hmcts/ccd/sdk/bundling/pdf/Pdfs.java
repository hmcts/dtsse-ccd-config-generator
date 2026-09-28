package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlePresentation;
import uk.gov.hmcts.ccd.sdk.bundling.api.ConfidentialMarking;
import uk.gov.hmcts.ccd.sdk.bundling.api.PageNumbers;

final class Pdfs {
  static final String DESCRIPTION =
      "This is the description, it should really be wrapped but it is not currently. "
          + "The table limit is 255 characters anyway.";

  interface Builder {
    void build(PDDocument document) throws IOException;
  }

  private Pdfs() {
  }

  static Path fixture(String name) {
    URL resource = Pdfs.class.getResource("/fixtures/em-stitching/" + name);
    if (resource == null) {
      throw new IllegalArgumentException("No such fixture: " + name);
    }
    try {
      return Path.of(resource.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Bad fixture URI: " + name, e);
    }
  }

  static Path pdf(Path dir, String prefix, Builder builder) {
    return io(() -> {
      Path pdf = Files.createTempFile(Files.createDirectories(dir), prefix, ".pdf");
      try (PDDocument document = new PDDocument()) {
        builder.build(document);
        document.save(pdf.toFile());
      }
      return pdf;
    });
  }

  // Creates a PDF of the given number of pages, each showing the text once.
  static Path textPdf(Path dir, String text, int pages) {
    return pdf(dir, "test-input-", document -> {
      for (int i = 0; i < pages; i++) {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream contents = new PDPageContentStream(document, page)) {
          contents.beginText();
          contents.setFont(new PdfFonts().helvetica(), 12);
          contents.newLineAtOffset(100, 700);
          contents.showText(text);
          contents.endText();
        }
      }
    });
  }

  static BundlePresentation tocOnly() {
    return new BundlePresentation(true, false, false, PageNumbers.NONE, ConfidentialMarking.NONE);
  }

  static AssemblyItem doc(String title, Path pdf) {
    return new AssemblyItem(title, Optional.empty(), false, new PdfSource(pdf));
  }

  static AssemblyItem doc(String title, LocalDate date, Path pdf) {
    return new AssemblyItem(title, Optional.of(date), false, new PdfSource(pdf));
  }

  static AssemblyFolder folder(String title, AssemblyNode... children) {
    return new AssemblyFolder(title, Arrays.asList(children));
  }

  static AssemblyRequest request(BundlePresentation presentation, AssemblyNode... nodes) {
    return AssemblyRequest.of("Title of the bundle", "stitched.pdf", presentation, List.of(nodes));
  }

  // A request carrying DESCRIPTION (a two-line index heading) and optionally a title page.
  static AssemblyRequest described(BundlePresentation presentation, boolean titlePage,
      List<AssemblyNode> nodes) {
    return new AssemblyRequest("Title of the bundle", "stitched.pdf", Optional.of(DESCRIPTION),
        presentation, titlePage, nodes);
  }

  interface Io<T> {
    T call() throws IOException;
  }

  static <T> T io(Io<T> call) {
    try {
      return call.call();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static <T> T read(Path pdf, Function<PDDocument, T> reader) {
    return io(() -> {
      try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
        return reader.apply(document);
      }
    });
  }

  static String pageText(Path pdf, int page) {
    return pageText(pdf, page, page);
  }

  static String pageText(Path pdf, int first, int last) {
    return read(pdf, document -> io(() -> {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setStartPage(first);
      stripper.setEndPage(last);
      return stripper.getText(document);
    }));
  }

  static List<String> outline(Path pdf) {
    return read(pdf, document -> {
      List<String> entries = new ArrayList<>();
      PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
      for (PDOutlineItem child = outline == null ? null : outline.getFirstChild(); child != null;
          child = child.getNextSibling()) {
        appendOutline(child, 0, entries);
      }
      return entries;
    });
  }

  static List<Integer> internalLinkTargets(Path pdf, int page) {
    return links(pdf, page, link -> resolvePage(io(link::getDestination), link.getAction())).stream()
        .filter(target -> target >= 0).map(target -> target + 1).toList();
  }

  static List<PDRectangle> linkRects(Path pdf, int page) {
    return links(pdf, page, PDAnnotationLink::getRectangle);
  }

  // Reads every link annotation of a 1-based page, in order, while the document is still open.
  private static <T> List<T> links(Path pdf, int page, Function<PDAnnotationLink, T> reader) {
    return read(pdf, document -> io(() -> document.getPage(page - 1).getAnnotations().stream()
        .filter(PDAnnotationLink.class::isInstance).map(PDAnnotationLink.class::cast).map(reader)
        .toList()));
  }

  private static void appendOutline(PDOutlineItem item, int depth, List<String> entries) {
    int page = resolvePage(io(item::getDestination), item.getAction());
    entries.add("  ".repeat(depth) + item.getTitle() + (page >= 0 ? " -> " + (page + 1) : ""));
    for (PDOutlineItem child = item.getFirstChild(); child != null; child = child.getNextSibling()) {
      appendOutline(child, depth + 1, entries);
    }
  }

  private static int resolvePage(PDDestination destination, PDAction action) {
    if (destination == null && action instanceof PDActionGoTo goTo) {
      destination = io(goTo::getDestination);
    }
    return destination instanceof PDPageDestination pageDestination
        ? pageDestination.retrievePageNumber() : -1;
  }
}
