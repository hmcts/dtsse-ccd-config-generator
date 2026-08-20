package uk.gov.hmcts.ccd.sdk.bundling.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfSemanticsTest {

  @TempDir
  Path tempDir;

  private static Path fixture(String name) throws URISyntaxException {
    URL url = PdfSemanticsTest.class.getResource("/fixtures/em-stitching/" + name);
    assertThat(url).as("fixture " + name).isNotNull();
    return Path.of(url.toURI());
  }

  @Test
  void extractsPageCountAndTextFromSinglePagePdf() throws Exception {
    ObjectNode facts = PdfSemantics.extract(fixture("one-page.pdf"));

    assertThat(facts.get("pageCount").intValue()).isEqualTo(1);
    assertThat(facts.get("pages")).hasSize(1);
    assertThat(facts.get("pages").get(0).get("page").intValue()).isEqualTo(1);
    // em-stitching declares no explicit page labels on its fixtures.
    assertThat(facts.get("pageLabels").isNull()).isTrue();
  }

  @Test
  void extractsOutlineTreeWithResolvedPageTargets() throws Exception {
    ObjectNode facts = PdfSemantics.extract(fixture("outlined.pdf"));

    JsonNode outline = facts.get("outline");
    assertThat(outline.size()).isGreaterThan(0);
    List<JsonNode> items = flatten(outline);
    assertThat(items).allSatisfy(item -> {
      assertThat(item.get("title").textValue()).isNotBlank();
      if (!item.get("targetPage").isNull()) {
        assertThat(item.get("targetPage").intValue())
            .isBetween(1, facts.get("pageCount").intValue());
      }
    });
    // At least one outline entry must resolve to a real page, or the fixture is broken.
    assertThat(items).anyMatch(item -> !item.get("targetPage").isNull());
  }

  @Test
  void unresolvableNamedDestinationsExtractAsNullTargets() throws Exception {
    // outline_with_named.pdf's outline points at named destination "movies", but the fixture
    // carries no names dictionary at all — the destination is unresolvable even in the source
    // document, and the extractor must pin that as targetPage: null rather than fail.
    ObjectNode facts = PdfSemantics.extract(fixture("outline_with_named.pdf"));

    List<JsonNode> items = flatten(facts.get("outline"));
    assertThat(items).isNotEmpty();
    assertThat(items).allMatch(item -> item.get("targetPage").isNull());
  }

  @Test
  void resolvesNamedDestinationsThroughTheCatalog() throws Exception {
    Path pdf = tempDir.resolve("named-destination.pdf");
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      PDPage second = new PDPage();
      document.addPage(second);

      PDPageFitDestination destination = new PDPageFitDestination();
      destination.setPage(second);
      PDDestinationNameTreeNode destinations = new PDDestinationNameTreeNode();
      destinations.setNames(Map.of("target", destination));
      PDDocumentNameDictionary names =
          new PDDocumentNameDictionary(document.getDocumentCatalog());
      names.setDests(destinations);
      document.getDocumentCatalog().setNames(names);

      PDOutlineItem item = new PDOutlineItem();
      item.setTitle("Named");
      item.setDestination(new PDNamedDestination("target"));
      PDDocumentOutline outline = new PDDocumentOutline();
      outline.addLast(item);
      document.getDocumentCatalog().setDocumentOutline(outline);
      document.save(pdf.toFile());
    }

    ObjectNode facts = PdfSemantics.extract(pdf);

    assertThat(facts.get("outline")).hasSize(1);
    assertThat(facts.get("outline").get(0).get("title").textValue()).isEqualTo("Named");
    assertThat(facts.get("outline").get(0).get("targetPage").intValue()).isEqualTo(2);
  }

  @Test
  void resolvesGoToActionOutlineTargets() throws Exception {
    ObjectNode facts = PdfSemantics.extract(fixture("outline_with_actions.pdf"));

    List<JsonNode> items = flatten(facts.get("outline"));
    assertThat(items).isNotEmpty();
    assertThat(items).anyMatch(item -> !item.get("targetPage").isNull());
  }

  @Test
  void extractionRoundTripsAgainstCommittedCharacterisationGoldens() throws Exception {
    // Every committed golden PDF must extract to exactly its committed facts.json.
    // This pins the extractor itself: if PdfSemantics changes shape, the goldens must be
    // regenerated with the harness in scripts/bundling-characterisation.
    URL url = PdfSemanticsTest.class.getResource("/characterisation");
    assertThat(url).as("characterisation goldens").isNotNull();
    Path root = Path.of(url.toURI());
    List<Path> goldens = new ArrayList<>();
    try (Stream<Path> scenarios = Files.list(root)) {
      scenarios.filter(Files::isDirectory).forEach(goldens::add);
    }
    assertThat(goldens).isNotEmpty();
    for (Path scenario : goldens) {
      Path golden = scenario.resolve("golden.pdf");
      Path facts = scenario.resolve("facts.json");
      assertThat(facts).as("facts.json in " + scenario.getFileName()).exists();
      if (!Files.exists(golden)) {
        continue;
      }
      assertThat((JsonNode) PdfSemantics.extract(golden))
          .as("extract(golden.pdf) for scenario " + scenario.getFileName())
          .isEqualTo(PdfSemantics.readFacts(facts));
    }
  }

  @Test
  void extractsPageNumberStampsWithCoordinates() throws Exception {
    Path pdf = tempDir.resolve("stamped.pdf");
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        // A stamp where em-stitching's PaginationStyle.topLeft prints it (~20pt from edges).
        text(cs, font, 20, page.getMediaBox().getHeight() - 20, "7");
        // Digits in body content, mid-page: must not be classified as a stamp.
        text(cs, font, 100, 400, "300");
      }
      document.save(pdf.toFile());
    }

    ObjectNode facts = PdfSemantics.extract(pdf);
    JsonNode stamps = facts.get("pages").get(0).get("pageNumberStamps");

    assertThat(stamps).hasSize(1);
    assertThat(stamps.get(0).get("value").intValue()).isEqualTo(7);
    assertThat(stamps.get(0).get("x").intValue()).isEqualTo(20);
    assertThat(stamps.get(0).get("y").intValue())
        .isCloseTo(Math.round(new PDPage().getMediaBox().getHeight() - 20), within(12));
    assertThat(facts.get("pages").get(0).get("text").asText()).contains("300");
  }

  @Test
  void extractsImageFactsIncludingImagesNestedInFormXObjects() throws Exception {
    Path pdf = tempDir.resolve("imaged.pdf");
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      PDImageXObject image = PDImageXObject.createFromFileByExtension(
          fixture("schmcts.png").toFile(), document);
      try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
        cs.drawImage(image, 100, 400);
      }
      document.save(pdf.toFile());
    }

    ObjectNode facts = PdfSemantics.extract(pdf);
    JsonNode images = facts.get("pages").get(0).get("images");

    assertThat(images).hasSize(1);
    assertThat(images.get(0).get("width").intValue()).isPositive();
    assertThat(images.get(0).get("height").intValue()).isPositive();
    assertThat(images.get(0).get("pixelSha256").textValue()).startsWith("px:").hasSize(67);

    // The same source image must hash identically wherever it is embedded — that is what
    // lets the watermark golden pin the watermark image across producers.
    ObjectNode again = PdfSemantics.extract(pdf);
    assertThat(again.get("pages").get(0).get("images")).isEqualTo(images);
  }

  private static void text(PDPageContentStream cs, PDType1Font font, float x, float y,
      String value) throws Exception {
    cs.beginText();
    cs.setFont(font, 13);
    cs.newLineAtOffset(x, y);
    cs.showText(value);
    cs.endText();
  }

  @Test
  void jsonRenderingIsDeterministic() throws Exception {
    ObjectNode first = PdfSemantics.extract(fixture("outlined.pdf"));
    ObjectNode second = PdfSemantics.extract(fixture("outlined.pdf"));

    assertThat(PdfSemantics.toJson(first)).isEqualTo(PdfSemantics.toJson(second));
    assertThat(PdfSemantics.toJson(first)).endsWith("\n").doesNotContain("\r");
  }

  private static List<JsonNode> flatten(JsonNode outline) {
    List<JsonNode> items = new ArrayList<>();
    collect(outline, items);
    return items;
  }

  private static void collect(JsonNode nodes, List<JsonNode> into) {
    for (JsonNode node : nodes) {
      into.add(node);
      collect(node.get("children"), into);
    }
  }
}
