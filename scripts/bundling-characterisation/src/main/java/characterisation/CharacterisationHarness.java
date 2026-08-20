package characterisation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.data.util.Pair;
import uk.gov.hmcts.ccd.sdk.bundling.testsupport.PdfSemantics;
import uk.gov.hmcts.reform.em.stitching.domain.Bundle;
import uk.gov.hmcts.reform.em.stitching.domain.BundleDocument;
import uk.gov.hmcts.reform.em.stitching.domain.BundleFolder;
import uk.gov.hmcts.reform.em.stitching.domain.DocumentImage;
import uk.gov.hmcts.reform.em.stitching.domain.enumeration.ImageRendering;
import uk.gov.hmcts.reform.em.stitching.domain.enumeration.ImageRenderingLocation;
import uk.gov.hmcts.reform.em.stitching.domain.enumeration.PageNumberFormat;
import uk.gov.hmcts.reform.em.stitching.domain.enumeration.PaginationStyle;
import uk.gov.hmcts.reform.em.stitching.pdf.PDFMerger;
import uk.gov.hmcts.reform.em.stitching.pdf.PDFWatermark;

/**
 * Runs em-stitching-api's real rendering classes (PDFMerger, TableOfContents, PDFOutline,
 * PDFWatermark, PDFUtility) against the service's own fixtures for a fixed set of
 * characterisation scenarios, and records per scenario the golden PDF plus its extracted
 * semantic facts as JSON.
 *
 * <p>Bundle constructions are ported from em-stitching's own
 * {@code PDFMergerTestUtil}/{@code PDFMergerTest}/{@code PDFMergerCoversheetsTest} so the
 * scenarios characterise exactly what the service's unit suite guarantees.
 *
 * <p>The em-stitching checkout is read-only input: fixtures are copied to a temp directory
 * before any operation that could write (PDFWatermark saves over its input file in place).
 */
public final class CharacterisationHarness {

  private interface Producer {
    File produce() throws IOException;
  }

  private record MergeCase(Bundle bundle, Map<BundleDocument, File> docs) {
  }

  private static Path fixtures;
  private static Path tmp;

  private CharacterisationHarness() {
  }

  public static void main(String[] args) throws Exception {
    Path emDir = Path.of(required("emStitchingDir"));
    Path outRoot = Path.of(required("outputDir"));
    boolean writePdfs = Boolean.parseBoolean(System.getProperty("writeGoldenPdfs", "true"));
    fixtures = emDir.resolve("src/test/resources/test-files");
    if (!Files.isDirectory(fixtures)) {
      throw new IllegalStateException("em-stitching fixtures not found at " + fixtures);
    }
    tmp = Files.createTempDirectory("bundling-characterisation");

    Map<String, Producer> scenarios = new LinkedHashMap<>();
    scenarios.put("toc-flat", () -> merge(flatCase(true, PaginationStyle.topLeft, null), null));
    scenarios.put("toc-off-flat", () -> merge(flatCase(false, PaginationStyle.topLeft, null), null));
    scenarios.put("toc-coverpage",
        () -> merge(flatCase(true, PaginationStyle.topLeft, null), fixture("FL-FRM-GOR-ENG-12345.pdf")));
    scenarios.put("toc-off-coverpage",
        () -> merge(flatCase(false, PaginationStyle.topLeft, null), fixture("FL-FRM-GOR-ENG-12345.pdf")));
    scenarios.put("document-coversheets", () -> merge(documentCoversheetsCase(), null));
    scenarios.put("folder-and-document-coversheets", () -> merge(folderedCase(), null));
    scenarios.put("folder-coversheets-nested", () -> merge(subFolderedCase(), null));
    scenarios.put("multiline-titles", () -> merge(multilineTitlesCase(), null));
    scenarios.put("multi-page-toc", () -> merge(multiPageTocCase(), null));
    scenarios.put("page-number-format-page-range",
        () -> merge(flatCase(true, PaginationStyle.topLeft, PageNumberFormat.PAGE_RANGE), null));
    scenarios.put("pagination-off", () -> merge(paginationOffCase(), null));
    scenarios.put("pagination-top-right", () -> merge(multiFolderedCase(), null));
    scenarios.put("preserved-outlines", () -> merge(outlinedCase(false), null));
    scenarios.put("document-subtitles-outlines", () -> merge(outlinedCase(true), null));
    scenarios.put("multiline-titles-many-docs", () -> merge(multilineManyDocsCase(), null));
    scenarios.put("special-character-titles", () -> merge(specialCharactersCase(), null));
    scenarios.put("image-watermark", CharacterisationHarness::imageWatermark);

    List<String> failures = new ArrayList<>();
    for (Map.Entry<String, Producer> scenario : scenarios.entrySet()) {
      String name = scenario.getKey();
      try {
        File output = scenario.getValue().produce();
        ObjectNode facts = PdfSemantics.extract(output.toPath());
        validate(name, facts);
        Path dir = outRoot.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("facts.json"), PdfSemantics.toJson(facts),
            StandardCharsets.UTF_8);
        if (writePdfs) {
          Files.copy(output.toPath(), dir.resolve("golden.pdf"),
              StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.printf("OK   %-35s pages=%-4d outline=%-3d links=%-3d (%d bytes)%n",
            name, facts.get("pageCount").intValue(), facts.get("outline").size(),
            facts.get("links").size(), output.length());
      } catch (Exception e) {
        failures.add(name + ": " + e);
        System.out.printf("FAIL %-35s %s%n", name, e);
      }
    }

    if (!failures.isEmpty()) {
      System.err.println("Characterisation failures: " + failures);
      System.exit(1);
    }
    System.out.println("All " + scenarios.size() + " scenarios written to " + outRoot);
  }

  private static void validate(String name, ObjectNode facts) {
    if (facts.get("pageCount").intValue() < 1) {
      throw new IllegalStateException(name + ": empty document");
    }
    StringBuilder allText = new StringBuilder();
    facts.get("pages").forEach(p -> allText.append(p.get("text").textValue()));
    if (allText.toString().isBlank()) {
      throw new IllegalStateException(name + ": no extractable text");
    }
  }

  private static String required(String property) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing system property " + property);
    }
    return value;
  }

  private static File fixture(String name) {
    File file = fixtures.resolve(name).toFile();
    if (!file.isFile()) {
      throw new IllegalStateException("Missing fixture " + file);
    }
    return file;
  }

  private static File merge(MergeCase mergeCase, File coverPage) throws IOException {
    return new PDFMerger().merge(mergeCase.bundle(), mergeCase.docs(), coverPage);
  }

  // --- Scenarios, ported from em-stitching's PDFMergerTestUtil / PDFMergerTest ---

  /** PDFMergerTestUtil.createFlatTestBundle, paired with its standard two documents. */
  private static MergeCase flatCase(boolean toc, PaginationStyle pagination,
      PageNumberFormat format) throws IOException {
    Bundle bundle = newBundle("Title of the bundle",
        "This is the description, it should really be wrapped but it is not currently. "
            + "The table limit is 255 characters anyway.");
    bundle.setHasTableOfContents(toc);
    bundle.setPaginationStyle(pagination);
    if (format != null) {
      bundle.setPageNumberFormat(format);
    }

    BundleDocument doc1 = newDocument(1L, "Bundle Doc 1", 0);
    BundleDocument doc2 = newDocument(1L, "Bundle Doc 2", 0);
    bundle.getDocuments().add(doc1);
    bundle.getDocuments().add(doc2);

    Map<BundleDocument, File> docs = new LinkedHashMap<>();
    docs.put(doc1, createTestPdf("Title of the bundle", 2));
    docs.put(doc2, fixture("annotationTemplate.pdf"));
    return new MergeCase(bundle, docs);
  }

  /** PDFMergerTest.testPageNumbersPrintedOnCorrectPagesWithPaginationOptionAndCoverSheetsSelected. */
  private static MergeCase documentCoversheetsCase() {
    Bundle bundle = newBundle("Title of the bundle",
        "This is the description, it should really be wrapped but it is not currently. "
            + "The table limit is 255 characters anyway.");
    bundle.setHasTableOfContents(false);
    bundle.setHasCoversheets(true);
    bundle.setPaginationStyle(PaginationStyle.topLeft);

    Map<BundleDocument, File> docs = new LinkedHashMap<>();
    for (int i = 0; i < 2; i++) {
      BundleDocument document = newDocument((long) i, "Document", 0);
      bundle.getDocuments().add(document);
      docs.put(document, fixture("annotationTemplate.pdf"));
    }
    return new MergeCase(bundle, docs);
  }

  /** PDFMergerTestUtil.createFolderedTestBundle: doc+folder coversheets on, pagination off. */
  private static MergeCase folderedCase() throws IOException {
    Bundle bundle = newBundle("Title of the bundle",
        "This is the description, it should really be wrapped but it is not currently. "
            + "The table limit is 255 characters anyway.");
    bundle.setHasTableOfContents(true);
    bundle.setHasCoversheets(true);
    bundle.setHasFolderCoversheets(true);
    bundle.setPaginationStyle(PaginationStyle.off);

    BundleDocument doc1 = newDocument(1L, "Bundle Doc 1", 1);
    BundleDocument doc2 = newDocument(2L, "Bundle Doc 2", 2);

    BundleFolder folder = new BundleFolder();
    folder.setFolderName("Folder 1");
    folder.setDescription("This is folder 1");
    folder.setSortIndex(1);
    folder.getDocuments().add(doc1);
    bundle.getFolders().add(folder);
    bundle.getDocuments().add(doc2);

    Map<BundleDocument, File> docs = new LinkedHashMap<>();
    docs.put(doc1, createTestPdf("Title of the bundle", 2));
    docs.put(doc2, fixture("annotationTemplate.pdf"));
    return new MergeCase(bundle, docs);
  }

  /** PDFMergerTestUtil.createSubFolderedTestBundle: folder coversheets with nested folders. */
  private static MergeCase subFolderedCase() throws IOException {
    Bundle bundle = newBundle("Title of the bundle",
        "This is the description, it should really be wrapped but it is not currently."
            + " The table limit is 255 characters anyway.");
    bundle.setHasTableOfContents(true);
    bundle.setHasCoversheets(false);
    bundle.setHasFolderCoversheets(true);
    bundle.setPaginationStyle(PaginationStyle.bottomLeft);

    BundleFolder folder1 = new BundleFolder();
    folder1.setFolderName("Folder 1");
    folder1.setDescription("The is a top level folder, Folder 1");
    folder1.setSortIndex(1);

    BundleDocument doc1 = newDocument(1L, "This is a doc inside a folder", 1);

    BundleFolder subfolder1 = new BundleFolder();
    subfolder1.setFolderName("Folder 2");
    subfolder1.setDescription("This is a subfolder, Folder 2");
    subfolder1.setSortIndex(2);

    BundleDocument doc2 = newDocument(2L, "This is a doc inside a subfolder", 1);

    subfolder1.getDocuments().add(doc2);
    folder1.getFolders().add(subfolder1);
    folder1.getDocuments().add(doc1);
    bundle.getFolders().add(folder1);

    // Empty folder tree, filtered out by getSortedItems (as in the original util).
    BundleFolder folder3 = new BundleFolder();
    folder3.setFolderName("Folder 3");
    folder3.setDescription("The 333 is a top level folder, Folder 3");
    folder3.setSortIndex(1);
    BundleFolder subfolder3 = new BundleFolder();
    subfolder3.setFolderName("sub Folder 3");
    subfolder3.setDescription("This is a subfolder, Folder 2");
    folder3.getFolders().add(subfolder3);
    bundle.getFolders().add(folder3);

    Map<BundleDocument, File> docs = new LinkedHashMap<>();
    docs.put(doc1, createTestPdf("Title of the bundle", 2));
    docs.put(doc2, fixture("annotationTemplate.pdf"));
    return new MergeCase(bundle, docs);
  }

  /** PDFMergerTestUtil.createFlatTestBundleWithMultilineTitles (115 repeats, as the util). */
  private static MergeCase multilineTitlesCase() throws IOException {
    String veryLong = " Very long".repeat(115);
    Bundle bundle = newBundle("Title of the bundle",
        "This is the description, it should really be wrapped but it is not currently."
            + " The table limit is 255 characters anyway.");
    bundle.setHasTableOfContents(true);
    bundle.setPaginationStyle(PaginationStyle.topLeft);

    BundleDocument doc1 = newDocument(1L, "Bundle Doc 1" + veryLong, 0);
    BundleDocument doc2 = newDocument(1L, "Bundle Doc 2" + veryLong, 0);
    bundle.getDocuments().add(doc1);
    bundle.getDocuments().add(doc2);

    Map<BundleDocument, File> docs = new LinkedHashMap<>();
    docs.put(doc1, createTestPdf("Title of the bundle", 2));
    docs.put(doc2, fixture("annotationTemplate.pdf"));
    return new MergeCase(bundle, docs);
  }

  /**
   * Scenario shaped on PDFMergerTest.testMultipleTableOfContentsPages, but deliberately not
   * parameter-identical to it: the cited test uses 200 documents and the flat bundle's
   * topLeft pagination; this scenario uses 50 documents (enough to spill the TOC onto a
   * second page while keeping the committed golden ~100 pages/45KB instead of ~400 pages)
   * and pagination off (so the multi-page-TOC fact is isolated from stamping, which the
   * pagination scenarios pin separately).
   */
  private static MergeCase multiPageTocCase() throws IOException {
    Bundle bundle = newBundle("Title of the bundle",
        "This is the description, it should really be wrapped but it is not currently. "
            + "The table limit is 255 characters anyway.");
    bundle.setHasTableOfContents(true);
    bundle.setPaginationStyle(PaginationStyle.off);

    File testPdf = createTestPdf("Title of the bundle", 2);
    Map<BundleDocument, File> docs = new LinkedHashMap<>();
    for (int i = 0; i < 50; i++) {
      BundleDocument document = newDocument((long) i, "Bundle Doc " + i, 0);
      bundle.getDocuments().add(document);
      docs.put(document, testPdf);
    }
    return new MergeCase(bundle, docs);
  }

  /** PDFMergerTest.testPageNumbersNotPrintedOnCorrectPagesWithPaginationOptionOff. */
  private static MergeCase paginationOffCase() {
    Bundle bundle = newBundle("Title of the bundle",
        "This is the description, it should really be wrapped but it is not currently. "
            + "The table limit is 255 characters anyway.");
    bundle.setHasTableOfContents(true);
    bundle.setPaginationStyle(PaginationStyle.off);

    Map<BundleDocument, File> docs = new LinkedHashMap<>();
    for (int i = 0; i < 4; i++) {
      BundleDocument document = newDocument((long) i, "Document Title", 0);
      bundle.getDocuments().add(document);
      docs.put(document, fixture("annotationTemplate.pdf"));
    }
    return new MergeCase(bundle, docs);
  }

  /** PDFMergerTestUtil.createMultiFolderedTestBundle: two folders, pagination topRight. */
  private static MergeCase multiFolderedCase() throws IOException {
    Bundle bundle = newBundle("Title of the bundle",
        "This is the description, it should really be wrapped but it is not currently. "
            + "The table limit is 255 characters anyway.");
    bundle.setHasTableOfContents(true);
    bundle.setHasCoversheets(false);
    bundle.setHasFolderCoversheets(true);
    bundle.setPaginationStyle(PaginationStyle.topRight);

    BundleDocument doc1 = newDocument(1L, "Bundle Doc 1", 1);
    BundleDocument doc2 = newDocument(2L, "A separate description - this one is of folder 2", 2);

    BundleFolder folder1 = new BundleFolder();
    folder1.setFolderName("Folder 1");
    folder1.setDescription("The first folder description - this is for folder 1");
    folder1.setSortIndex(1);
    folder1.getDocuments().add(doc1);

    BundleFolder folder2 = new BundleFolder();
    folder2.setFolderName("Folder 2");
    folder2.setDescription("This is folder 2");
    folder2.setSortIndex(2);
    folder2.getDocuments().add(doc2);

    bundle.getFolders().add(folder1);
    bundle.getFolders().add(folder2);

    Map<BundleDocument, File> docs = new LinkedHashMap<>();
    docs.put(doc1, createTestPdf("Title of the bundle", 2));
    docs.put(doc2, fixture("annotationTemplate.pdf"));
    return new MergeCase(bundle, docs);
  }

  /**
   * Risky-boundary scenario: multiline titles x many documents, so variable-height TOC
   * entries (alternating ~13-line and 1-line titles) break across TOC page boundaries.
   */
  private static MergeCase multilineManyDocsCase() throws IOException {
    String veryLong = " Very long".repeat(115);
    Bundle bundle = newBundle("Title of the bundle",
        "This is the description, it should really be wrapped but it is not currently."
            + " The table limit is 255 characters anyway.");
    bundle.setHasTableOfContents(true);
    bundle.setPaginationStyle(PaginationStyle.off);

    File testPdf = createTestPdf("Title of the bundle", 2);
    Map<BundleDocument, File> docs = new LinkedHashMap<>();
    for (int i = 1; i <= 8; i++) {
      String title = (i % 2 == 0) ? "Bundle Doc " + i : "Bundle Doc " + i + veryLong;
      BundleDocument document = newDocument((long) i, title, 0);
      bundle.getDocuments().add(document);
      docs.put(document, testPdf);
    }
    return new MergeCase(bundle, docs);
  }

  /**
   * PDFMergerTestUtil.createFlatTestBundleWithSpecialChars: Polish diacritics in bundle and
   * document titles. PDFUtility.sanitizeText strips non-WinAnsi characters from drawn text
   * while the outline keeps them (PDFMergerTest.specialCharactersInIndexPage). The cited test
   * stitches Potential_Energy_PDF.pdf; annotationTemplate.pdf is used here instead purely to
   * keep the committed golden small — the behaviour under characterisation is title handling,
   * not the document body.
   */
  private static MergeCase specialCharactersCase() {
    Bundle bundle = newBundle("ąćęłńóśźż",
        "This is the description, it should be wrapped now. The table limit is 1000 characters.");
    bundle.setHasTableOfContents(true);
    bundle.setPaginationStyle(PaginationStyle.topLeft);

    BundleDocument doc1 = newDocument(1L, "ąćęłńóśźż", 0);
    bundle.getDocuments().add(doc1);

    Map<BundleDocument, File> docs = new LinkedHashMap<>();
    docs.put(doc1, fixture("annotationTemplate.pdf"));
    return new MergeCase(bundle, docs);
  }

  /**
   * Sources with preserved outlines: outlined.pdf, outline_with_actions.pdf (GoTo actions),
   * outline_with_named.pdf (named destinations). With subtitles off, em-stitching drops the
   * source outlines entirely (PDFOutline.copyOutline early-returns); with subtitles on they
   * are copied into the merged outline with destinations remapped, and the TOC gains
   * subtitle link lines.
   */
  private static MergeCase outlinedCase(boolean documentSubtitles) {
    Bundle bundle = newBundle("Outline bundle",
        "Bundle of documents whose outlines must survive stitching.");
    bundle.setHasTableOfContents(true);
    bundle.setHasDocumentSubtitles(documentSubtitles);
    bundle.setPaginationStyle(PaginationStyle.off);

    BundleDocument doc1 = newDocument(1L, "Outlined Document", 1);
    BundleDocument doc2 = newDocument(2L, "Outline With Actions", 2);
    BundleDocument doc3 = newDocument(3L, "Outline With Named Destinations", 3);
    bundle.getDocuments().add(doc1);
    bundle.getDocuments().add(doc2);
    bundle.getDocuments().add(doc3);

    Map<BundleDocument, File> docs = new LinkedHashMap<>();
    docs.put(doc1, fixture("outlined.pdf"));
    docs.put(doc2, fixture("outline_with_actions.pdf"));
    docs.put(doc3, fixture("outline_with_named.pdf"));
    return new MergeCase(bundle, docs);
  }

  /**
   * PDFWatermarkTest: image watermark (schmcts.png) on every page, opaque, centred.
   * PDFWatermark saves over its input file, so the fixture is copied to a temp file first
   * (the service's own tests only get away with passing the classpath copy).
   */
  private static File imageWatermark() throws IOException {
    Path input = tmp.resolve("watermark-input.pdf");
    Files.copy(fixture("TEST_INPUT_FILE.pdf").toPath(), input,
        StandardCopyOption.REPLACE_EXISTING);

    DocumentImage documentImage = new DocumentImage();
    documentImage.setDocmosisAssetId("schmcts.png");
    documentImage.setImageRendering(ImageRendering.OPAQUE);
    documentImage.setImageRenderingLocation(ImageRenderingLocation.ALL_PAGES);
    documentImage.setCoordinateX(50);
    documentImage.setCoordinateY(50);

    BundleDocument document = newDocument(1L, "Watermarked Document", 0);
    Pair<BundleDocument, File> result = new PDFWatermark().processDocumentWatermark(
        fixture("schmcts.png"), Pair.of(document, input.toFile()), documentImage);
    return result.getSecond();
  }

  // --- Helpers ---

  private static Bundle newBundle(String title, String description) {
    Bundle bundle = new Bundle();
    bundle.setBundleTitle(title);
    bundle.setDescription(description);
    bundle.setHasTableOfContents(false);
    bundle.setHasCoversheets(false);
    bundle.setHasFolderCoversheets(false);
    return bundle;
  }

  private static BundleDocument newDocument(Long id, String title, int sortIndex) {
    BundleDocument document = new BundleDocument();
    document.setId(id);
    document.setDocTitle(title);
    document.setSortIndex(sortIndex);
    return document;
  }

  /** PDFMergerTestUtil.createTestPdf: n pages, each showing the given text. */
  private static File createTestPdf(String text, int pages) throws IOException {
    File pdf = Files.createTempFile(tmp, "test_input", ".pdf").toFile();
    try (PDDocument doc = new PDDocument()) {
      for (int i = 0; i < pages; i++) {
        PDPage page = new PDPage();
        doc.addPage(page);
        try (PDPageContentStream contents = new PDPageContentStream(doc, page)) {
          contents.beginText();
          contents.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
          contents.newLineAtOffset(100, 700);
          contents.showText(text);
          contents.endText();
        }
      }
      doc.save(pdf);
    }
    return pdf;
  }
}
