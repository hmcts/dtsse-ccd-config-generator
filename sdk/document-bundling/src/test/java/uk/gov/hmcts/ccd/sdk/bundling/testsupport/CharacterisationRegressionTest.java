package uk.gov.hmcts.ccd.sdk.bundling.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlePresentation;
import uk.gov.hmcts.ccd.sdk.bundling.api.ConfidentialMarking;
import uk.gov.hmcts.ccd.sdk.bundling.api.PageNumbers;
import uk.gov.hmcts.ccd.sdk.bundling.api.WatermarkPreset;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssembledItem;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyFolder;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyItem;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyNode;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyRequest;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyResult;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.PdfBundleAssembler;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.PdfSource;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.Watermark;

class CharacterisationRegressionTest {
  private static final String FLAT_DESCRIPTION =
      "This is the description, it should really be wrapped but it is not currently. "
          + "The table limit is 255 characters anyway.";
  private static final String OUTLINE_DESCRIPTION =
      "Bundle of documents whose outlines must survive stitching.";
  private static final String VERY_LONG = " Very long".repeat(115);

  @TempDir
  Path tmp;

  // --- Scenarios ---

  @Test
  void tocFlat() throws IOException {
    verify("toc-flat", flatRequest(true, PageNumbers.TOP_RIGHT_N),
        new Options(1, true, false, false));
  }

  @Test
  void tocOffFlat() throws IOException {
    verify("toc-off-flat", flatRequest(false, PageNumbers.TOP_RIGHT_N),
        new Options(0, true, false, false));
  }

  @Test
  void tocCoverpage() throws IOException {
    // The cover page is first, bookmarked "Cover Page", unnumbered; the index follows it.
    verify("toc-coverpage", flatRequest(true, PageNumbers.TOP_RIGHT_N, coverPage()),
        new Options(1, 1, true, false, false));
  }

  @Test
  void tocOffCoverpage() throws IOException {
    verify("toc-off-coverpage", flatRequest(false, PageNumbers.TOP_RIGHT_N, coverPage()),
        new Options(1, 0, true, false, false));
  }

  @Test
  void imageWatermark() throws IOException {
    // Divergence 11: the golden is one watermarked document, not a bundle, and its text layer is
    // corrupt. Assemble it bare (no index, no numbering) and pin the images to the golden and
    // the text to the original document.
    Path source = fixture("TEST_INPUT_FILE.pdf");
    AssemblyRequest request = new AssemblyRequest("Watermark", "stitched.pdf", Optional.empty(),
        presentation(false, false, false, PageNumbers.NONE), false, Optional.empty(),
        Optional.of(new Watermark(fixture("schmcts.png"), WatermarkPreset.Scope.ALL_PAGES,
            WatermarkPreset.Rendering.OPAQUE)),
        List.of(doc("Watermarked", source)));
    Map<String, Object> expected = PdfSemantics.readFacts(
        goldenDir("image-watermark").resolve("facts.json"));
    Map<String, Object> original = PdfSemantics.extract(source);

    AssemblyResult result = new PdfBundleAssembler().assemble(request, tmp.resolve("work-wm"));
    Map<String, Object> actual = PdfSemantics.extract(result.outputPdf());

    assertThat(actual.get("pageCount")).isEqualTo(expected.get("pageCount"));
    for (int page = 0; page < num(expected.get("pageCount")); page++) {
      Map<String, Object> actualPage = obj(arr(actual.get("pages")).get(page));
      assertThat(actualPage.get("images")).as("watermark image facts on page %d", page + 1)
          .isEqualTo(obj(arr(expected.get("pages")).get(page)).get("images"));
      assertThat(actualPage.get("text")).as("readable text on page %d", page + 1)
          .isEqualTo(obj(arr(original.get("pages")).get(page)).get("text"));
    }
  }

  @Test
  void documentCoversheets() throws IOException {
    AssemblyRequest request = request("Title of the bundle", FLAT_DESCRIPTION,
        presentation(false, false, true, PageNumbers.TOP_RIGHT_N),
        List.of(doc("Document", fixture("annotationTemplate.pdf")),
            doc("Document", fixture("annotationTemplate.pdf"))));
    verify("document-coversheets", request, new Options(0, true, false, false));
  }

  @Test
  void folderAndDocumentCoversheets() throws IOException {
    AssemblyRequest request = request("Title of the bundle", FLAT_DESCRIPTION,
        presentation(true, true, true, PageNumbers.NONE),
        List.of(
            folder("Folder 1", doc("Bundle Doc 1", textPdf("Title of the bundle", 2))),
            doc("Bundle Doc 2", fixture("annotationTemplate.pdf"))));
    verify("folder-and-document-coversheets", request, new Options(1, false, false, false));
  }

  @Test
  void folderCoversheetsNested() throws IOException {
    AssemblyRequest request = request("Title of the bundle", FLAT_DESCRIPTION,
        presentation(true, true, false, PageNumbers.BOTTOM_RIGHT_N),
        List.of(
            folder("Folder 1",
                doc("This is a doc inside a folder", textPdf("Title of the bundle", 2)),
                folder("Folder 2",
                    doc("This is a doc inside a subfolder",
                        fixture("annotationTemplate.pdf")))),
            folder("Folder 3", folder("sub Folder 3"))));
    verify("folder-coversheets-nested", request, new Options(1, true, false, false));
  }

  @Test
  void multilineTitles() throws IOException {
    AssemblyRequest request = request("Title of the bundle", FLAT_DESCRIPTION,
        presentation(true, false, false, PageNumbers.TOP_RIGHT_N),
        List.of(doc("Bundle Doc 1" + VERY_LONG, textPdf("Title of the bundle", 2)),
            doc("Bundle Doc 2" + VERY_LONG, fixture("annotationTemplate.pdf"))));
    verify("multiline-titles", request, new Options(1, true, false, false));
  }

  @Test
  void multiPageToc() throws IOException {
    Path testPdf = textPdf("Title of the bundle", 2);
    List<AssemblyNode> items = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      items.add(doc("Bundle Doc " + i, testPdf));
    }
    AssemblyRequest request = request("Title of the bundle", FLAT_DESCRIPTION,
        presentation(true, false, false, PageNumbers.NONE), items);
    verify("multi-page-toc", request, new Options(2, false, false, false));
  }

  @Test
  void pageNumberFormatPageRange() throws IOException {
    // The PAGE_RANGE column is an em-stitching request enum with no SDK analogue: the same SDK
    // output must match this golden too, everywhere outside the exempt TOC column text.
    verify("page-number-format-page-range", flatRequest(true, PageNumbers.TOP_RIGHT_N),
        new Options(1, true, false, false));
  }

  @Test
  void paginationOff() throws IOException {
    List<AssemblyNode> items = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      items.add(doc("Document Title", fixture("annotationTemplate.pdf")));
    }
    AssemblyRequest request = request("Title of the bundle", FLAT_DESCRIPTION,
        presentation(true, false, false, PageNumbers.NONE), items);
    verify("pagination-off", request, new Options(1, false, false, false));
  }

  @Test
  void paginationTopRight() throws IOException {
    AssemblyRequest request = request("Title of the bundle", FLAT_DESCRIPTION,
        presentation(true, true, false, PageNumbers.TOP_RIGHT_N),
        List.of(
            folder("Folder 1", doc("Bundle Doc 1", textPdf("Title of the bundle", 2))),
            folder("Folder 2", doc("A separate description - this one is of folder 2",
                fixture("annotationTemplate.pdf")))));
    // The golden's own topRight style exists as an SDK preset: stamps pinned exactly.
    verify("pagination-top-right", request, new Options(1, false, false, false));
  }

  @Test
  void preservedOutlines() throws IOException {
    // Divergence 1: the golden (subtitles off) drops source outlines; the SDK keeps them. The
    // golden tree must be exactly the SDK tree's top levels, and the SDK's full tree must be
    // exactly the grafted tree of the document-subtitles-outlines golden (same bundle, same
    // page geometry, source outlines included).
    verify("preserved-outlines", outlinedRequest(), new Options(1, false, true, false));
  }

  @Test
  void documentSubtitlesOutlines() throws IOException {
    // Divergence 4: the golden's TOC subtitle lines and their (sometimes detached) links are
    // absent from the SDK output; the grafted outline tree itself must match exactly.
    verify("document-subtitles-outlines", outlinedRequest(), new Options(1, false, false, true));
  }

  @Test
  void multilineTitlesManyDocs() throws IOException {
    Path testPdf = textPdf("Title of the bundle", 2);
    List<AssemblyNode> items = new ArrayList<>();
    for (int i = 1; i <= 8; i++) {
      String title = (i % 2 == 0) ? "Bundle Doc " + i : "Bundle Doc " + i + VERY_LONG;
      items.add(doc(title, testPdf));
    }
    AssemblyRequest request = request("Title of the bundle", FLAT_DESCRIPTION,
        presentation(true, false, false, PageNumbers.NONE), items);
    verify("multiline-titles-many-docs", request, new Options(2, false, false, false));
  }

  @Test
  void specialCharacterTitles() throws IOException {
    AssemblyRequest request = request("ąćęłńóśźż",
        "This is the description, it should be wrapped now."
            + " The table limit is 1000 characters.",
        presentation(true, false, false, PageNumbers.TOP_RIGHT_N),
        List.of(doc("ąćęłńóśźż", fixture("annotationTemplate.pdf"))));
    verify("special-character-titles", request, new Options(1, true, false, false));
  }

  @Test
  void extractionRoundTripsAgainstCommittedCharacterisationGoldens() throws IOException {
    // Every committed golden.pdf must extract to exactly its committed facts.json: this pins the
    // extractor itself, so a change to PdfSemantics forces the goldens to be regenerated.
    List<Path> scenarios;
    try (Stream<Path> entries = Files.list(goldenDir("toc-flat").getParent())) {
      scenarios = entries.filter(Files::isDirectory).sorted().toList();
    }
    assertThat(scenarios).hasSize(17);
    for (Path scenario : scenarios) {
      Map<String, Object> facts = PdfSemantics.readFacts(scenario.resolve("facts.json"));
      Map<String, Object> extracted = PdfSemantics.extract(scenario.resolve("golden.pdf"));
      if (scenario.getFileName().toString().equals("image-watermark")) {
        // Divergence 11: em-stitching corrupted this golden's text layer and PDFBox extracts the
        // garbage differently per platform, so the committed facts carry no page text.
        extracted = withoutPageText(extracted);
      }
      assertThat(extracted)
          .as("extract(golden.pdf) for scenario " + scenario.getFileName())
          .isEqualTo(facts);
      assertThat(Files.readString(scenario.resolve("facts.json")))
          .as("facts.json of %s is in the canonical compact form", scenario.getFileName())
          .isEqualTo(PdfSemantics.toJson(facts));
    }
  }

  private static Map<String, Object> withoutPageText(Map<String, Object> facts) {
    Map<String, Object> copy = new java.util.LinkedHashMap<>(facts);
    List<Object> pages = new java.util.ArrayList<>();
    for (Object page : arr(facts.get("pages"))) {
      Map<String, Object> pageCopy = new java.util.LinkedHashMap<>(obj(page));
      pageCopy.put("text", "");
      pages.add(pageCopy);
    }
    copy.put("pages", pages);
    return copy;
  }

  // --- Comparison machinery ---

  // coverPages: supplied cover pages before the index; tocPages: index pages in the golden (text
  // exempt under divergences 4 and 5); stampXExempt: the golden's pagination style has no SDK
  // preset (x exempt, value and y pinned); goldenOutlineIsPrefix: divergence 1;
  // dropGoldenTocSubtitleLinks: divergence 4.
  private record Options(int coverPages, int tocPages, boolean stampXExempt,
      boolean goldenOutlineIsPrefix, boolean dropGoldenTocSubtitleLinks) {
    Options(int tocPages, boolean stampXExempt, boolean goldenOutlineIsPrefix,
        boolean dropGoldenTocSubtitleLinks) {
      this(0, tocPages, stampXExempt, goldenOutlineIsPrefix, dropGoldenTocSubtitleLinks);
    }

    boolean isIndexPage(int page) {
      return page > coverPages && page <= coverPages + tocPages;
    }
  }

  private void verify(String scenario, AssemblyRequest request, Options options)
      throws IOException {
    Map<String, Object> expected = PdfSemantics.readFacts(goldenDir(scenario).resolve("facts.json"));
    AssemblyResult result = new PdfBundleAssembler().assemble(request,
        tmp.resolve("work-" + scenario));
    Map<String, Object> actual = PdfSemantics.extract(result.outputPdf());
    try {
      comparePageCountAndLabels(scenario, expected, actual);
      comparePages(scenario, expected, actual, options, result);
      compareOutline(scenario, expected, actual, options);
      compareLinks(scenario, expected, actual, options, result.outputPdf());
    } catch (AssertionError e) {
      System.out.println("=== " + scenario + " ACTUAL ===\n" + PdfSemantics.toJson(actual));
      System.out.println("=== " + scenario + " GOLDEN ===\n" + PdfSemantics.toJson(expected));
      throw e;
    }
  }

  private void comparePageCountAndLabels(String scenario, Map<String, Object> expected,
      Map<String, Object> actual) {
    assertThat(actual.get("pageCount")).as("%s: pageCount", scenario)
        .isEqualTo(expected.get("pageCount"));
    assertThat(actual.get("pageLabels")).as("%s: pageLabels", scenario)
        .isEqualTo(expected.get("pageLabels"));
  }

  private void comparePages(String scenario, Map<String, Object> expected, Map<String, Object> actual,
      Options options, AssemblyResult result) {
    int pageCount = num(expected.get("pageCount"));
    for (int page = 1; page <= pageCount; page++) {
      Map<String, Object> goldenPage = obj(arr(expected.get("pages")).get(page - 1));
      Map<String, Object> actualPage = obj(arr(actual.get("pages")).get(page - 1));
      String goldenText = text(goldenPage.get("text"));
      String actualText = text(actualPage.get("text"));

      if (options.isIndexPage(page)) {
        // Divergences 4 and 5: index-page text exempt; replacements asserted below.
        if (page == options.coverPages() + 1) {
          assertThat(actualText).as("%s: index heading", scenario).contains("Index Page");
        }
      } else if (goldenText.contains("Back to index")) {
        // Divergence 10: the back-link line moves to the visible top of the cover sheet.
        assertThat(withoutBackToIndex(actualText))
            .as("%s: cover-sheet text of page %d (back-link line exempt)", scenario, page)
            .isEqualTo(withoutBackToIndex(goldenText));
        assertThat(actualText).as("%s: page %d keeps the back link", scenario, page)
            .contains("Back to index");
      } else {
        assertThat(actualText).as("%s: text of page %d", scenario, page)
            .isEqualTo(goldenText);
      }

      assertThat(stamps(actualPage, options.stampXExempt()))
          .as("%s: page-number stamps of page %d", scenario, page)
          .isEqualTo(stamps(goldenPage, options.stampXExempt()));
      assertThat(actualPage.get("images"))
          .as("%s: image facts of page %d", scenario, page)
          .isEqualTo(goldenPage.get("images"));
    }

    if (options.tocPages() > 0) {
      // Divergence 5 replacement: every entry's 1-based start page appears in the index text.
      StringBuilder tocText = new StringBuilder();
      for (int page = options.coverPages() + 1; page <= options.coverPages() + options.tocPages();
          page++) {
        tocText.append(text(obj(arr(actual.get("pages")).get(page - 1)).get("text"))).append('\n');
      }
      for (AssembledItem item : result.items()) {
        assertThat(tocText.toString())
            .as("%s: index carries the start page of '%s'", scenario, item.title())
            .contains(String.valueOf(item.startPage()));
      }
    }
  }

  private List<String> stamps(Map<String, Object> page, boolean xexempt) {
    List<String> rendered = new ArrayList<>();
    for (Object node : arr(page.get("pageNumberStamps"))) {
      Map<String, Object> stamp = obj(node);
      rendered.add(text(stamp.get("value"))
          + (xexempt ? "" : "@x" + text(stamp.get("x")))
          + "@y" + text(stamp.get("y")));
    }
    return rendered;
  }

  private void compareOutline(String scenario, Map<String, Object> expected, Map<String, Object> actual,
      Options options) throws IOException {
    if (!options.goldenOutlineIsPrefix()) {
      assertThat(flattenOutline(actual.get("outline"), Integer.MAX_VALUE))
          .as("%s: outline tree", scenario)
          .isEqualTo(flattenOutline(expected.get("outline"), Integer.MAX_VALUE));
      return;
    }
    // Divergence 1: the golden dropped source outlines. Its whole tree must equal the SDK
    // tree's top levels, and the SDK's full tree must equal the grafted tree preserved by the
    // document-subtitles-outlines golden (same bundle, same page geometry).
    assertThat(flattenOutline(actual.get("outline"), 1))
        .as("%s: outline top levels", scenario)
        .isEqualTo(flattenOutline(expected.get("outline"), Integer.MAX_VALUE));
    Map<String, Object> grafted = PdfSemantics.readFacts(
        goldenDir("document-subtitles-outlines").resolve("facts.json"));
    assertThat(flattenOutline(actual.get("outline"), Integer.MAX_VALUE))
        .as("%s: full outline vs the grafted golden", scenario)
        .isEqualTo(flattenOutline(grafted.get("outline"), Integer.MAX_VALUE));
  }

  private static List<String> flattenOutline(Object items, int maxDepth) {
    List<String> lines = new ArrayList<>();
    appendOutline(items, 0, maxDepth, lines);
    return lines;
  }

  private static void appendOutline(Object items, int depth, int maxDepth, List<String> out) {
    if (depth > maxDepth) {
      return;
    }
    for (Object node : arr(items)) {
      Map<String, Object> item = obj(node);
      out.add(depth + "|" + text(item.get("title")) + "|" + item.get("bold")
          + "|" + text(item.get("targetPage")));
      appendOutline(item.get("children"), depth + 1, maxDepth, out);
    }
  }

  private void compareLinks(String scenario, Map<String, Object> expected, Map<String, Object> actual,
      Options options, Path actualPdf) throws IOException {
    List<Map<String, Object>> goldenLinks = new ArrayList<>();
    for (Object node : arr(expected.get("links"))) {
      Map<String, Object> link = obj(node);
      if (options.dropGoldenTocSubtitleLinks()
          && options.isIndexPage(num(link.get("sourcePage")))
          && num(arr(link.get("rect")).get(0)) != 50) {
        continue; // divergence 4: the golden's TOC subtitle links are absent from the SDK.
      }
      goldenLinks.add(link);
    }
    List<Map<String, Object>> actualLinks = arr(actual.get("links")).stream()
        .map(CharacterisationRegressionTest::obj).toList();

    assertThat(actualLinks.stream()
        .map(l -> text(l.get("sourcePage")) + "->" + text(l.get("targetPage")))
        .collect(Collectors.toList()))
        .as("%s: link source and target pages, in order", scenario)
        .isEqualTo(goldenLinks.stream()
            .map(l -> text(l.get("sourcePage")) + "->" + text(l.get("targetPage")))
            .collect(Collectors.toList()));

    // Every SDK link rectangle must lie within its source page's media box (divergence 10:
    // the golden's own cover-sheet back-link rects are off-page and are not compared).
    List<PDRectangle> pageBoxes = new ArrayList<>();
    try (PDDocument document = Loader.loadPDF(actualPdf.toFile())) {
      for (PDPage page : document.getPages()) {
        pageBoxes.add(page.getMediaBox());
      }
    }
    for (Map<String, Object> link : actualLinks) {
      PDRectangle box = pageBoxes.get(num(link.get("sourcePage")) - 1);
      List<Object> rect = arr(link.get("rect"));
      assertThat(rect).as("%s: link on page %s has a rectangle", scenario,
          link.get("sourcePage")).isNotNull();
      float[] bounds = {box.getLowerLeftX(), box.getLowerLeftY(), box.getUpperRightX(), box.getUpperRightY()};
      for (int i = 0; i < 4; i++) {
        float value = num(rect.get(i));
        assertThat(i < 2 ? value >= bounds[i] - 1f : value <= bounds[i] + 1f)
            .as("%s: link rect[%d]=%s on page %s within the media box", scenario, i, value,
                link.get("sourcePage"))
            .isTrue();
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> obj(Object node) {
    return (Map<String, Object>) node;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> arr(Object node) {
    return (List<Object>) node;
  }

  private static int num(Object node) {
    return (Integer) node;
  }

  private static String text(Object node) {
    return String.valueOf(node);
  }

  private static String withoutBackToIndex(String text) {
    return Arrays.stream(text.split("\n"))
        .filter(line -> !line.equals("Back to index"))
        .collect(Collectors.joining("\n"));
  }

  // --- Scenario construction helpers ---

  private AssemblyRequest flatRequest(boolean toc, PageNumbers numbers) {
    return flatRequest(toc, numbers, Optional.empty());
  }

  private AssemblyRequest flatRequest(boolean toc, PageNumbers numbers, Optional<Path> coverPage) {
    return new AssemblyRequest("Title of the bundle", "stitched.pdf",
        Optional.of(FLAT_DESCRIPTION), presentation(toc, false, false, numbers), false,
        coverPage, Optional.empty(),
        List.of(doc("Bundle Doc 1", textPdf("Title of the bundle", 2)),
            doc("Bundle Doc 2", fixture("annotationTemplate.pdf"))));
  }

  private static Optional<Path> coverPage() {
    return Optional.of(fixture("FL-FRM-GOR-ENG-12345.pdf"));
  }

  private AssemblyRequest outlinedRequest() {
    return request("Outline bundle", OUTLINE_DESCRIPTION,
        presentation(true, false, false, PageNumbers.NONE),
        List.of(doc("Outlined Document", fixture("outlined.pdf")),
            doc("Outline With Actions", fixture("outline_with_actions.pdf")),
            doc("Outline With Named Destinations", fixture("outline_with_named.pdf"))));
  }

  private static BundlePresentation presentation(boolean toc, boolean sectionCovers,
      boolean documentCovers, PageNumbers numbers) {
    return new BundlePresentation(toc, sectionCovers, documentCovers, numbers,
        ConfidentialMarking.NONE);
  }

  private static AssemblyRequest request(String title, String description,
      BundlePresentation presentation, List<AssemblyNode> items) {
    return new AssemblyRequest(title, "stitched.pdf", Optional.of(description), presentation,
        false, Optional.empty(), Optional.empty(), items);
  }

  private static AssemblyItem doc(String title, Path pdf) {
    return new AssemblyItem(title, Optional.empty(), false, new PdfSource(pdf));
  }

  private static AssemblyFolder folder(String title, AssemblyNode... children) {
    return new AssemblyFolder(title, Arrays.asList(children));
  }

  private static Path goldenDir(String scenario) {
    URL resource = CharacterisationRegressionTest.class
        .getResource("/characterisation/" + scenario + "/facts.json");
    if (resource == null) {
      throw new IllegalStateException("No golden for scenario " + scenario);
    }
    try {
      return Path.of(resource.toURI()).getParent();
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Path fixture(String name) {
    URL resource = CharacterisationRegressionTest.class
        .getResource("/fixtures/em-stitching/" + name);
    if (resource == null) {
      throw new IllegalArgumentException("No such fixture: " + name);
    }
    try {
      return Path.of(resource.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private Path textPdf(String text, int pages) {
    try {
      Path pdf = Files.createTempFile(Files.createDirectories(tmp.resolve("inputs")),
          "test-input-", ".pdf");
      try (PDDocument document = new PDDocument()) {
        for (int i = 0; i < pages; i++) {
          PDPage page = new PDPage();
          document.addPage(page);
          try (PDPageContentStream contents = new PDPageContentStream(document, page)) {
            contents.beginText();
            contents.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
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
}
