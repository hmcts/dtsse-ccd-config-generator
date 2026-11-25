package uk.gov.hmcts.ccd.sdk.doctoolkit.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.text.PDFTextStripper;
import uk.gov.hmcts.ccd.sdk.doctoolkit.MissingDocuments;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PageNumbers;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfResult;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocToolkitSystemUser;
import uk.gov.hmcts.ccd.sdk.doctoolkit.cdam.CaseLocator;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;

/** Fixture files and readers for the toolkit's own tests. */
public final class ToolkitFixtures {
  public static final DocToolkitSystemUser SYSTEM_USER = () -> "Bearer system-user";
  public static final AuthTokenGenerator SERVICE_TOKENS = () -> "Bearer service";
  public static final CaseLocator ONE_CASE_TYPE =
      caseId -> new CaseLocator.CaseType("E2E", "DIVORCE");

  private ToolkitFixtures() {
  }

  /**
   * A request with every bundle feature on: title page, contents, section cover sheets, page
   * numbers, confidential marking, and placeholder pages for missing documents.
   */
  public static PdfRequest.Builder bundle(String title) {
    return PdfRequest.builder(title)
        .titlePage()
        .tableOfContents()
        .sectionCoverSheets()
        .pageNumbers(PageNumbers.BOTTOM_CENTRE_N_OF_M)
        .confidentialMarking()
        .missingDocuments(MissingDocuments.PLACEHOLDER);
  }

  /** A file under {@code src/test/resources/fixtures}. */
  public static byte[] fixture(String name) {
    try (InputStream in = ToolkitFixtures.class.getResourceAsStream("/fixtures/" + name)) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The result's PDF, loaded. */
  public static PDDocument load(PdfResult result) throws IOException {
    return Loader.loadPDF(result.file().toFile());
  }

  /** All the text in a PDF. */
  public static String text(PDDocument document) throws IOException {
    return new PDFTextStripper().getText(document);
  }

  /** The text of one page, 1-based. */
  public static String text(PDDocument document, int page) throws IOException {
    PDFTextStripper stripper = new PDFTextStripper();
    stripper.setStartPage(page);
    stripper.setEndPage(page);
    return stripper.getText(document);
  }

  /** Every bookmark title, depth first. */
  public static List<String> outline(PDDocument document) {
    List<String> titles = new ArrayList<>();
    collect(document.getDocumentCatalog().getDocumentOutline(), titles);
    return titles;
  }

  private static void collect(PDOutlineNode node, List<String> into) {
    if (node == null) {
      return;
    }
    for (PDOutlineItem child : node.children()) {
      into.add(child.getTitle());
      collect(child, into);
    }
  }

  /** Fails if anything is left in the directory. */
  public static void assertNothingLeftBehind(Path directory) {
    try (Stream<Path> entries = Files.list(directory)) {
      assertThat(entries).as("files left under " + directory).isEmpty();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
