package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlePresentation;
import uk.gov.hmcts.ccd.sdk.bundling.api.ConfidentialMarking;
import uk.gov.hmcts.ccd.sdk.bundling.api.PageNumbers;

/**
 * Test-input builders: em-stitching fixtures, generated PDFs, and assembly-model shorthand.
 */
final class TestPdfs {

  private TestPdfs() {
  }

  /** Resolves one of the em-stitching fixtures on the test classpath. */
  static Path fixture(String name) {
    URL resource = TestPdfs.class.getResource("/fixtures/em-stitching/" + name);
    if (resource == null) {
      throw new IllegalArgumentException("No such fixture: " + name);
    }
    try {
      return Path.of(resource.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Bad fixture URI: " + name, e);
    }
  }

  /** Creates a PDF of {@code pages} pages, each showing {@code text} once. */
  static Path textPdf(Path dir, String text, int pages) {
    try {
      Path pdf = Files.createTempFile(dir, "test-input-", ".pdf");
      try (PDDocument document = new PDDocument()) {
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
        document.save(pdf.toFile());
      }
      return pdf;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Creates a one-page PDF whose outline nests {@code depth} items, "Level 0".."Level depth". */
  static Path deepOutlinePdf(Path dir, int depth) {
    try {
      Path pdf = Files.createTempFile(dir, "deep-outline-", ".pdf");
      try (PDDocument document = new PDDocument()) {
        PDPage page = new PDPage();
        document.addPage(page);
        PDDocumentOutline outline = new PDDocumentOutline();
        document.getDocumentCatalog().setDocumentOutline(outline);
        PDOutlineItem parent = new PDOutlineItem();
        parent.setTitle("Level 0");
        parent.setDestination(page);
        outline.addLast(parent);
        for (int i = 1; i <= depth; i++) {
          PDOutlineItem child = new PDOutlineItem();
          child.setTitle("Level " + i);
          child.setDestination(page);
          parent.addLast(child);
          parent = child;
        }
        document.save(pdf.toFile());
      }
      return pdf;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Creates a one-page PDF carrying an empty (childless) document outline. */
  static Path emptyOutlinePdf(Path dir) {
    try {
      Path pdf = Files.createTempFile(dir, "empty-outline-", ".pdf");
      try (PDDocument document = new PDDocument()) {
        document.addPage(new PDPage());
        document.getDocumentCatalog().setDocumentOutline(new PDDocumentOutline());
        document.save(pdf.toFile());
      }
      return pdf;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String sha256(Path file) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A presentation with a table of contents and nothing else. */
  static BundlePresentation tocOnly() {
    return new BundlePresentation(true, false, false, PageNumbers.NONE, ConfidentialMarking.NONE);
  }

  /** A presentation with no table of contents, cover sheets, numbering or marking. */
  static BundlePresentation plain() {
    return new BundlePresentation(false, false, false, PageNumbers.NONE,
        ConfidentialMarking.NONE);
  }

  static AssemblyItem doc(String title, Path pdf) {
    return new AssemblyItem(title, Optional.empty(), false, new PdfSource(pdf));
  }

  static AssemblyItem doc(String title, LocalDate date, Path pdf) {
    return new AssemblyItem(title, Optional.of(date), false, new PdfSource(pdf));
  }

  static AssemblyItem confidentialDoc(String title, Path pdf) {
    return new AssemblyItem(title, Optional.empty(), true, new PdfSource(pdf));
  }

  static AssemblyFolder folder(String title, AssemblyNode... children) {
    return new AssemblyFolder(title, Arrays.asList(children));
  }

  static AssemblyRequest request(BundlePresentation presentation, AssemblyNode... nodes) {
    return AssemblyRequest.of("Title of the bundle", "stitched.pdf", presentation,
        Arrays.asList(nodes));
  }

  static AssemblyRequest requestWithDescription(BundlePresentation presentation,
      String description, AssemblyNode... nodes) {
    return new AssemblyRequest("Title of the bundle", "stitched.pdf",
        Optional.of(description), presentation, false, Optional.empty(), Arrays.asList(nodes));
  }

  static List<AssemblyNode> nodes(AssemblyNode... nodes) {
    return Arrays.asList(nodes);
  }
}
