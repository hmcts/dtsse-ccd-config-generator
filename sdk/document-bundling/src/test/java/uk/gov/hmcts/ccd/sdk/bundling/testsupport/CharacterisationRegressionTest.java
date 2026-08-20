package uk.gov.hmcts.ccd.sdk.bundling.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
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
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssembledItem;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyFolder;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyItem;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyNode;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyRequest;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyResult;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.PdfBundleAssembler;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.PdfSource;

/**
 * The rendering-parity regression suite: rebuilds each characterisation scenario through
 * {@link PdfBundleAssembler} (same document tree, same fixture files, same presentation
 * options), extracts the result with {@link PdfSemantics#extract}, and compares the facts with
 * the committed golden generated from em-stitching-api's real rendering classes.
 *
 * <h2>Golden inputs</h2>
 *
 * <p>Each scenario directory under {@code src/test/resources/characterisation/<scenario>/}
 * holds {@code facts.json} (the authoritative semantic golden, produced by
 * {@link PdfSemantics#extract} run over em-stitching's output) and {@code golden.pdf} (the
 * raw em-stitching output, kept for eyeballing and for
 * {@link PdfSemanticsTest#extractionRoundTripsAgainstCommittedCharacterisationGoldens()}).
 * The em-stitching commit each golden was generated from, the exact bundle configuration of
 * every scenario, and the regeneration procedure are documented in
 * {@code scripts/bundling-characterisation/README.md}.
 *
 * <h2>How the comparison works</h2>
 *
 * <p>The comparison is <em>semantic, never byte-for-byte</em>, and per-field:
 *
 * <ul>
 *   <li><b>{@code pageCount}, {@code pageLabels}:</b> exact equality.</li>
 *   <li><b>{@code pages[].text}:</b> exact equality. The fact is already position-sorted and
 *       whitespace-normalised (lines trimmed, internal whitespace collapsed, empty lines
 *       dropped), so content-stream draw order and exact glyph spacing are tolerated by
 *       construction — what is pinned is the visual reading order and content. Exception:
 *       divergence (11) below for watermarked pages.</li>
 *   <li><b>{@code pages[].pageNumberStamps}:</b> exact equality of value, x and y — a stamp
 *       printed in the wrong corner or with the wrong number is a regression.</li>
 *   <li><b>{@code pages[].images}:</b> exact equality of width/height/pixel-hash sets. The
 *       hash is over the decoded raster, so recompression by a different producer is
 *       tolerated; different pixels are not.</li>
 *   <li><b>{@code outline}:</b> exact equality of the tree — titles, order, nesting, bold
 *       flags, resolved target pages — except where divergences (1)–(3) below apply.</li>
 *   <li><b>{@code links}:</b> {@code sourcePage}, entry order and {@code targetPage} pinned
 *       exactly; {@code rect} must lie within the source page's media box, coordinates
 *       otherwise free (the SDK lays text out at fractionally different offsets; what matters
 *       is that the link exists on the right page, is on-page, and goes to the right place).
 *       Exception: divergence (10) below for cover-sheet "Back to index" links.</li>
 * </ul>
 *
 * <h2>Preset scope note</h2>
 *
 * <p>The SDK's {@link PageNumbers} presets deliberately expose only approved positions
 * (bottom-centre, bottom-right, top-right). Goldens generated with em-stitching's
 * {@code topLeft}/{@code bottomLeft} styles are rebuilt with the nearest approved preset
 * (same edge, right-aligned), so for those scenarios the stamp's {@code value} and {@code y}
 * are pinned exactly and {@code x} is exempt; {@code pagination-top-right} pins all three.
 * The stamp's digit line inside {@code pages[].text} is unaffected (same value, same edge,
 * same reading order).
 *
 * <h2>Excluded scenarios</h2>
 *
 * <p>{@code toc-coverpage} and {@code toc-off-coverpage} exercise em-stitching's
 * supplied-cover-page feature (a Docmosis-rendered PDF passed alongside the bundle), which has
 * no SDK analogue — the SDK's generated title page is a different, deliberately-designed page.
 * {@code image-watermark} is compared in the pdf package's {@code AdversarialReviewTest}
 * because reproducing it requires the package-private {@code WatermarkRenderer} (it is a
 * watermarked single document, not an assembled bundle).
 *
 * <h2>Deliberate divergences (from the PDF port's declared list and its review)</h2>
 *
 * <p>Every behaviour the SDK intentionally does not reproduce is enumerated here with the
 * field it exempts and the replacement expectation; each also gets its own asserting test in
 * the pdf package. Any difference from a golden not covered below is a regression.
 *
 * <ol>
 *   <li><b>Source outlines preserved unconditionally.</b> em-stitching drops source outlines
 *       when document subtitles are off. Exempts: {@code outline} subtree comparison for
 *       source-document children in subtitles-off scenarios (e.g. preserved-outlines) —
 *       replacement: the SDK output must contain AT LEAST the golden's outline items, plus
 *       the source documents' own outline trees with correctly remapped page targets.</li>
 *   <li><b>Outlines rebuilt, not COS-grafted.</b> No COS-level artefacts (stray
 *       {@code /Filter} entries on outline items, reused COS keys). Exempts: nothing in the
 *       facts (facts are COS-independent; the rebuild copies titles, nesting, bold/italic
 *       styling and remapped targets); byte-level comparison was never in scope.</li>
 *   <li><b>Named destinations resolved to explicit destinations.</b> Unresolvable named
 *       destinations become destination-less bookmarks instead of dangling references.
 *       Exempts: {@code outline[].targetPage} for named-destination items — replacement:
 *       where the golden has a resolvable target the SDK must match it; where the golden has
 *       {@code targetPage: null} from an unresolvable name, the SDK item must also have
 *       {@code targetPage: null} (destination-less), never a wrong page.</li>
 *   <li><b>No TOC subtitle lines and no detached-page links.</b> The SDK does not print
 *       outline-derived subtitle lines in the TOC and never creates links to pages outside
 *       the page tree. Exempts: TOC-page {@code pages[].text} subtitle lines and
 *       {@code links} entries with {@code targetPage: null} in document-subtitles scenarios —
 *       replacement: those lines/links are absent, and no SDK-created link may ever have
 *       {@code targetPage: null}.</li>
 *   <li><b>TOC entry shows date + 1-based start page instead of the page-range /
 *       total-pages column.</b> Exempts: the TOC-page {@code pages[].text} column values
 *       ("2 pages" / "2 - 3") — replacement: each TOC entry line must carry the document's
 *       immutable date and its 1-based start page, and the start page must equal the page the
 *       entry's link targets.</li>
 *   <li><b>Watermark never mutates the input file, and failures propagate.</b> Exempts:
 *       nothing in the facts — behavioural divergence pinned by its own test (input file
 *       bytes unchanged after watermarking; watermark failure throws instead of silently
 *       returning the unwatermarked document).</li>
 *   <li><b>Structure-tree fallback warns and de-duplicates</b> instead of em-stitching's
 *       silent retry with a fresh empty structure tree. Exempts: nothing in the facts.</li>
 *   <li><b>No {@code pdfbox.fontcache} global mutation.</b> Exempts: nothing in the
 *       facts.</li>
 *   <li><b>Bounded stream cache spilling to workDir</b> instead of unbounded main-memory
 *       merging. Exempts: nothing in the facts.</li>
 *   <li><b>Cover-sheet "Back to index" link is on-page and clickable.</b> em-stitching draws
 *       it at a swapped-argument position so the golden link rectangles on folder/document
 *       cover sheets are off-page and the link text sits mid-page. Exempts:
 *       {@code links[].rect} for cover-sheet pages and the position (reading order) of the
 *       "Back to index" line within cover-sheet {@code pages[].text} — replacement: the SDK
 *       link's rect must lie fully within the page's media box and target the index page, and
 *       the line must be present on the cover sheet.</li>
 *   <li><b>Watermarked-page text is readable in the SDK.</b> em-stitching's watermark output
 *       has a corrupted text layer (it saves over the file it is lazily reading), so the
 *       image-watermark golden's {@code pages[].text} is stream-corruption garbage. Exempts:
 *       {@code pages[].text} for watermarked pages — replacement: the watermark image facts
 *       ({@code pages[].images}) must match the golden exactly, and the SDK's extracted text
 *       must be the ORIGINAL document's readable text (expected to exceed the golden, never
 *       merely differ from it).</li>
 * </ol>
 */
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

  // --- Comparison machinery ---

  /**
   * Per-scenario comparison options.
   *
   * @param tocPages how many index pages the golden has (their text is exempt under
   *     divergences 4 and 5, with the replacement expectations asserted instead)
   * @param stampXExempt whether the golden's pagination style has no SDK preset, exempting the
   *     stamp's x while pinning value and y (see the preset scope note)
   * @param goldenOutlineIsPrefix divergence 1: the golden tree is the SDK tree's top levels
   * @param dropGoldenTocSubtitleLinks divergence 4: the golden's TOC subtitle links are absent
   */
  private record Options(int tocPages, boolean stampXExempt, boolean goldenOutlineIsPrefix,
      boolean dropGoldenTocSubtitleLinks) {
  }

  private void verify(String scenario, AssemblyRequest request, Options options)
      throws IOException {
    JsonNode expected = PdfSemantics.readFacts(goldenDir(scenario).resolve("facts.json"));
    AssemblyResult result = new PdfBundleAssembler().assemble(request,
        tmp.resolve("work-" + scenario));
    JsonNode actual = PdfSemantics.extract(result.outputPdf());
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

  private void comparePageCountAndLabels(String scenario, JsonNode expected, JsonNode actual) {
    assertThat(actual.get("pageCount")).as("%s: pageCount", scenario)
        .isEqualTo(expected.get("pageCount"));
    assertThat(actual.get("pageLabels")).as("%s: pageLabels", scenario)
        .isEqualTo(expected.get("pageLabels"));
  }

  private void comparePages(String scenario, JsonNode expected, JsonNode actual,
      Options options, AssemblyResult result) {
    int pageCount = expected.get("pageCount").intValue();
    for (int page = 1; page <= pageCount; page++) {
      JsonNode goldenPage = expected.get("pages").get(page - 1);
      JsonNode actualPage = actual.get("pages").get(page - 1);
      String goldenText = goldenPage.get("text").asText();
      String actualText = actualPage.get("text").asText();

      if (page <= options.tocPages()) {
        // Divergences 4 and 5: index-page text exempt; replacements asserted below.
        if (page == 1) {
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
      for (int page = 1; page <= options.tocPages(); page++) {
        tocText.append(actual.get("pages").get(page - 1).get("text").asText()).append('\n');
      }
      for (AssembledItem item : result.items()) {
        assertThat(tocText.toString())
            .as("%s: index carries the start page of '%s'", scenario, item.title())
            .contains(String.valueOf(item.startPage()));
      }
    }
  }

  private List<String> stamps(JsonNode page, boolean xexempt) {
    List<String> rendered = new ArrayList<>();
    for (JsonNode stamp : page.get("pageNumberStamps")) {
      rendered.add(stamp.get("value").asText()
          + (xexempt ? "" : "@x" + stamp.get("x").asText())
          + "@y" + stamp.get("y").asText());
    }
    return rendered;
  }

  private void compareOutline(String scenario, JsonNode expected, JsonNode actual,
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
    JsonNode grafted = PdfSemantics.readFacts(
        goldenDir("document-subtitles-outlines").resolve("facts.json"));
    assertThat(flattenOutline(actual.get("outline"), Integer.MAX_VALUE))
        .as("%s: full outline vs the grafted golden", scenario)
        .isEqualTo(flattenOutline(grafted.get("outline"), Integer.MAX_VALUE));
  }

  private static List<String> flattenOutline(JsonNode items, int maxDepth) {
    List<String> lines = new ArrayList<>();
    appendOutline(items, 0, maxDepth, lines);
    return lines;
  }

  private static void appendOutline(JsonNode items, int depth, int maxDepth, List<String> out) {
    if (depth > maxDepth) {
      return;
    }
    for (JsonNode item : items) {
      out.add(depth + "|" + item.get("title").asText()
          + "|" + item.get("bold").asBoolean()
          + "|" + item.get("targetPage").asText());
      appendOutline(item.get("children"), depth + 1, maxDepth, out);
    }
  }

  private void compareLinks(String scenario, JsonNode expected, JsonNode actual,
      Options options, Path actualPdf) throws IOException {
    List<JsonNode> goldenLinks = new ArrayList<>();
    for (JsonNode link : expected.get("links")) {
      if (options.dropGoldenTocSubtitleLinks()
          && link.get("sourcePage").intValue() <= options.tocPages()
          && link.get("rect").get(0).intValue() != 50) {
        continue; // divergence 4: the golden's TOC subtitle links are absent from the SDK.
      }
      goldenLinks.add(link);
    }
    List<JsonNode> actualLinks = new ArrayList<>();
    actual.get("links").forEach(actualLinks::add);

    assertThat(actualLinks.stream()
        .map(l -> l.get("sourcePage").asText() + "->" + l.get("targetPage").asText())
        .collect(Collectors.toList()))
        .as("%s: link source and target pages, in order", scenario)
        .isEqualTo(goldenLinks.stream()
            .map(l -> l.get("sourcePage").asText() + "->" + l.get("targetPage").asText())
            .collect(Collectors.toList()));

    // Every SDK link rectangle must lie within its source page's media box (divergence 10:
    // the golden's own cover-sheet back-link rects are off-page and are not compared).
    List<PDRectangle> pageBoxes = new ArrayList<>();
    try (PDDocument document = Loader.loadPDF(actualPdf.toFile())) {
      for (PDPage page : document.getPages()) {
        pageBoxes.add(page.getMediaBox());
      }
    }
    for (JsonNode link : actualLinks) {
      PDRectangle box = pageBoxes.get(link.get("sourcePage").intValue() - 1);
      JsonNode rect = link.get("rect");
      assertThat(rect).as("%s: link on page %s has a rectangle", scenario,
          link.get("sourcePage")).isNotNull();
      float epsilon = 1f;
      assertThat(rect.get(0).floatValue())
          .as("%s: link llx on page %s", scenario, link.get("sourcePage"))
          .isGreaterThanOrEqualTo(box.getLowerLeftX() - epsilon);
      assertThat(rect.get(1).floatValue())
          .as("%s: link lly on page %s", scenario, link.get("sourcePage"))
          .isGreaterThanOrEqualTo(box.getLowerLeftY() - epsilon);
      assertThat(rect.get(2).floatValue())
          .as("%s: link urx on page %s", scenario, link.get("sourcePage"))
          .isLessThanOrEqualTo(box.getUpperRightX() + epsilon);
      assertThat(rect.get(3).floatValue())
          .as("%s: link ury on page %s", scenario, link.get("sourcePage"))
          .isLessThanOrEqualTo(box.getUpperRightY() + epsilon);
    }
  }

  private static String withoutBackToIndex(String text) {
    return Arrays.stream(text.split("\n"))
        .filter(line -> !line.equals("Back to index"))
        .collect(Collectors.joining("\n"));
  }

  // --- Scenario construction helpers ---

  private AssemblyRequest flatRequest(boolean toc, PageNumbers numbers) {
    return request("Title of the bundle", FLAT_DESCRIPTION,
        presentation(toc, false, false, numbers),
        List.of(doc("Bundle Doc 1", textPdf("Title of the bundle", 2)),
            doc("Bundle Doc 2", fixture("annotationTemplate.pdf"))));
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
        false, Optional.empty(), items);
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

  /** The characterisation harness's createTestPdf: n pages, each showing the given text. */
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
