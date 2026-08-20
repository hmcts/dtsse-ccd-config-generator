package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.doc;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.fixture;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.request;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.textPdf;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.tocOnly;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ports the scenarios of em-stitching's {@code PDFOutlineTest}: the root bundle bookmark, the
 * index-page bookmark, source outlines nested beneath each document's bookmark with destinations
 * remapped into the merged pages — including action-based and named-destination outlines — plus
 * deep and empty outlines.
 *
 * <p>Two documented divergences are pinned here: source outlines are preserved unconditionally
 * (the service drops them unless its subtitles flag is set), and named destinations are resolved
 * to explicit page destinations at copy time (the service leaves them dangling).
 */
class PdfBundleAssemblerOutlineTest {

  @TempDir
  Path tmp;

  private final PdfBundleAssembler assembler = new PdfBundleAssembler();
  private Path workDir;

  @BeforeEach
  void setUp() {
    workDir = tmp.resolve("work");
  }

  @Test
  void rootBundleBookmarkPointsAtTheFirstPage() throws IOException {
    AssemblyResult result = assembler.assemble(
        request(tocOnly(), doc("Bundle Doc 1", textPdf(tmp, "one", 1))), workDir);

    List<String> outline = Pdfs.outline(result.outputPdf());
    assertThat(outline.get(0)).isEqualTo("Title of the bundle -> 1");
    assertThat(outline.get(1)).isEqualTo("  Index Page -> 1");
    assertThat(outline.get(2)).isEqualTo("  Bundle Doc 1 -> 2");
  }

  @Test
  void sourceOutlinesAreNestedBeneathEachDocumentsBookmark() throws IOException {
    Path testInputFile = fixture("TEST_INPUT_FILE.pdf");
    Path outlined = fixture("outlined.pdf");
    int testInputPages = Pdfs.pageCount(testInputFile);

    AssemblyResult result = assembler.assemble(request(tocOnly(),
        doc("Bundle Doc 1", testInputFile), doc("Bundle Doc 2", outlined)), workDir);

    List<String> outline = Pdfs.outline(result.outputPdf());
    assertThat(outline).containsSubsequence(
        "Title of the bundle -> 1",
        "  Index Page -> 1",
        "  Bundle Doc 1 -> 2",
        "    Slide 1 -> 2",
        "  Bundle Doc 2 -> " + (2 + testInputPages),
        "    Title of the bundle -> " + (2 + testInputPages));
    // outlined.pdf is itself a previously stitched bundle: its own outline tree is preserved
    // beneath Bundle Doc 2, nesting intact.
    assertThat(outline).anyMatch(entry -> entry.startsWith("      Cover Page -> "));
    assertThat(outline).anyMatch(entry -> entry.startsWith("      Index Page -> "));
    assertThat(outline).anyMatch(entry -> entry.startsWith("      Folder 1 -> "));
  }

  @Test
  void sourceOutlinesArePreservedUnconditionally() throws IOException {
    // Documented divergence: em-stitching drops source outlines unless hasDocumentSubtitles is
    // set; the SDK has no such flag and always preserves them.
    Path outlined = fixture("outlined.pdf");

    AssemblyResult result = assembler.assemble(
        request(TestPdfs.plain(), doc("Bundle Doc 1", outlined)), workDir);

    assertThat(Pdfs.outline(result.outputPdf()))
        .containsSubsequence("  Bundle Doc 1 -> 1", "    Title of the bundle -> 1");
  }

  @Test
  void actionBasedOutlinesAreRemappedIntoTheMergedPages() throws IOException {
    Path withActions = fixture("outline_with_actions.pdf");
    Path withNamed = fixture("outline_with_named.pdf");

    AssemblyResult result = assembler.assemble(request(tocOnly(),
        doc("Bundle Doc 1", withActions), doc("Bundle Doc 2", withNamed)), workDir);

    // "instant info" and the film titles carry GoTo actions remapped onto merged pages;
    // "link to IMDB" is an external URI action, so it keeps no page destination.
    List<String> outline = Pdfs.outline(result.outputPdf());
    assertThat(outline).containsSubsequence(
        "  Bundle Doc 1 -> 2",
        "    2001: A Space Odyssey -> 2",
        "      link to IMDB",
        "      instant info -> 2",
        "    3-Iron -> 3",
        "      link to IMDB",
        "      instant info -> 3");
  }

  @Test
  void unresolvableNamedDestinationYieldsBookmarkWithoutDestination() throws IOException {
    // outline_with_named.pdf carries a named destination but no names dictionary, so the name is
    // unresolvable even in the source. Documented divergence: the SDK emits the bookmark without
    // a destination instead of one pointing at a detached page.
    Path withNamed = fixture("outline_with_named.pdf");

    AssemblyResult result = assembler.assemble(
        request(tocOnly(), doc("Bundle Doc 1", withNamed)), workDir);

    assertThat(Pdfs.outline(result.outputPdf())).contains("    link to test");
  }

  @Test
  void resolvableNamedDestinationsAreResolvedToExplicitPages() throws IOException {
    Path named = namedDestinationPdf();

    AssemblyResult result = assembler.assemble(
        request(tocOnly(), doc("Named Doc", named)), workDir);

    // Source page 2 resolves through the names dictionary; offset by the contents page.
    assertThat(Pdfs.outline(result.outputPdf())).containsSubsequence(
        "  Named Doc -> 2",
        "    jump by name -> 3");
  }

  @Test
  void deeplyNestedOutlinesAreCopiedInFull() throws IOException {
    Path deep = TestPdfs.deepOutlinePdf(tmp, 20);

    AssemblyResult result = assembler.assemble(
        request(tocOnly(), doc("Deep Document", deep)), workDir);

    List<String> outline = Pdfs.outline(result.outputPdf());
    assertThat(outline).contains("    Level 0 -> 2");
    assertThat(outline).contains("  ".repeat(2 + 20) + "Level 20 -> 2");
  }

  @Test
  void emptySourceOutlineLeavesTheDocumentBookmarkChildless() throws IOException {
    Path emptyOutline = TestPdfs.emptyOutlinePdf(tmp);

    AssemblyResult result = assembler.assemble(request(tocOnly(),
        doc("Bundle Doc 1", emptyOutline), doc("Bundle Doc 2", textPdf(tmp, "two", 1))), workDir);

    assertThat(Pdfs.outline(result.outputPdf())).containsExactly(
        "Title of the bundle -> 1",
        "  Index Page -> 1",
        "  Bundle Doc 1 -> 2",
        "  Bundle Doc 2 -> 3");
  }

  @Test
  void documentBookmarkPointsAtItsCoverSheetWhenCoverSheetsAreOn() throws IOException {
    var presentation = tocOnly().withDocumentCoverSheets(true);

    AssemblyResult result = assembler.assemble(
        request(presentation, doc("Bundle Doc 1", textPdf(tmp, "one", 1))), workDir);

    // Pages: contents, cover sheet, content page.
    assertThat(Pdfs.outline(result.outputPdf())).contains("  Bundle Doc 1 -> 2");
    assertThat(result.items()).containsExactly(new AssembledItem("Bundle Doc 1", 3, 1));
  }

  @Test
  void veryLongBookmarkTitlesAreTrimmed() throws IOException {
    String longTitle = "T".repeat(450);

    AssemblyResult result = assembler.assemble(
        request(tocOnly(), doc(longTitle, textPdf(tmp, "one", 1))), workDir);

    assertThat(Pdfs.outline(result.outputPdf()))
        .contains("  " + "T".repeat(399) + "... -> 2");
  }

  private Path namedDestinationPdf() throws IOException {
    Path pdf = tmp.resolve("named-destination.pdf");
    try (PDDocument document = new PDDocument()) {
      PDPage page1 = new PDPage();
      PDPage page2 = new PDPage();
      document.addPage(page1);
      document.addPage(page2);

      PDPageXYZDestination target = new PDPageXYZDestination();
      target.setPage(page2);
      PDDocumentNameDictionary names = new PDDocumentNameDictionary(document.getDocumentCatalog());
      PDDestinationNameTreeNode destinations = new PDDestinationNameTreeNode();
      Map<String, PDPageDestination> destinationsByName = new HashMap<>();
      destinationsByName.put("target", target);
      destinations.setNames(destinationsByName);
      names.setDests(destinations);
      document.getDocumentCatalog().setNames(names);

      PDDocumentOutline outline = new PDDocumentOutline();
      document.getDocumentCatalog().setDocumentOutline(outline);
      PDOutlineItem item = new PDOutlineItem();
      item.setTitle("jump by name");
      PDNamedDestination namedDestination = new PDNamedDestination();
      namedDestination.setNamedDestination("target");
      item.setDestination(namedDestination);
      outline.addLast(item);

      document.save(pdf.toFile());
    }
    return pdf;
  }
}
