package uk.gov.hmcts.ccd.sdk.converter.emit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.converter.Converter;
import uk.gov.hmcts.ccd.sdk.converter.ConverterFactory;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition;

/**
 * Pins the generate-mode <b>source layout</b> the converter emits — the adoption-decisive structural
 * contract the retrofit clone-regeneration relies on (findings #1/#2/#6). Runs the full converter
 * over the bundled {@code golden/minimal} fixture and asserts the emitted file tree matches the
 * reference-service idiom (nfdiv/sptribs): one event class per event under {@code <root>.event}, a
 * page class per wizard page under {@code <root>.event.page} for multi-page events (a single-page
 * event inlines its one page into the event class instead), access classes under
 * {@code <root>.access}, the config split by concern under {@code <root>}, and the model classes
 * under {@code <modelPackage>}.
 *
 * <p>The JSON byte-identity of this same layout is proven by {@code RoundTripTest}; this test guards
 * the <em>shape</em> so a regression in packaging or class-per-concern splitting fails fast without
 * needing the (tagged, slower) round-trip run.
 */
class GeneratedLayoutTest {

  @Test
  void minimalFixtureEmitsReferenceServiceLayout(@TempDir Path work) throws Exception {
    Path input = Path.of("src/test/resources/golden/minimal/input").toAbsolutePath();
    Path srcOut = work.resolve("src");
    String modelPackage = "uk.gov.hmcts.minimal.model";
    // The derived root package (uk.gov.hmcts.minimal.model -> uk.gov.hmcts.minimal.ccd) is what the
    // CLI would compute; wire it explicitly here since we build options directly.
    String rootPackage = "uk.gov.hmcts.minimal.ccd";

    Map<String, OverlayCondition> suffixes = Map.of(
        "prod", OverlayCondition.parse("CCD_DEF_ENV:prod"),
        "nonprod", OverlayCondition.parse("!CCD_DEF_ENV:prod"));

    ConversionOptions options = ConversionOptions.builder()
        .inputs(List.of(input))
        .caseTypeId("Minimal")
        .outputSrc(srcOut)
        .modelPackage(modelPackage)
        .configPackage(rootPackage)
        .overlaySuffixes(suffixes)
        .passthroughDir(work.resolve("pass"))
        .reportDir(work.resolve("report"))
        .eventsPerConfig(40)
        .emitApplication(true)
        .allowGaps(true)
        .build();

    Converter converter = ConverterFactory.create(options);
    converter.convert(options);

    List<String> tree;
    try (Stream<Path> paths = Files.walk(srcOut)) {
      tree = paths
          .filter(p -> p.toString().endsWith(".java"))
          .map(p -> srcOut.relativize(p).toString().replace('\\', '/'))
          .sorted()
          .collect(Collectors.toList());
    }

    // Events: one class per event under <root>.event (finding #1), PascalCase-named from the ID,
    // overlay events keeping their suffix (createCase, addNotes, closeCase + the two overlay events).
    assertThat(tree).contains(
        "uk/gov/hmcts/minimal/ccd/event/CreateCase.java",
        "uk/gov/hmcts/minimal/ccd/event/AddNotes.java",
        "uk/gov/hmcts/minimal/ccd/event/CloseCase.java");
    // Page classes under <root>.event.page (finding #2) only for MULTI-page events: createCase has
    // two pages so it keeps its page classes. A single-page event (addNotes) inlines its one page
    // into the event class instead, so no AddNotesPage1 class is emitted.
    assertThat(tree).contains(
        "uk/gov/hmcts/minimal/ccd/event/page/CreateCasePage1.java",
        "uk/gov/hmcts/minimal/ccd/event/page/CreateCasePage2.java");
    assertThat(tree).noneMatch(p -> p.contains("AddNotesPage"));
    // Access classes under <root>.access.
    assertThat(tree).anyMatch(p -> p.startsWith("uk/gov/hmcts/minimal/ccd/access/"));
    // Config split by concern under <root> (finding #6): CaseType always, plus the concerns present.
    assertThat(tree).contains(
        "uk/gov/hmcts/minimal/ccd/MinimalCaseType.java",
        "uk/gov/hmcts/minimal/ccd/MinimalGrants.java",
        "uk/gov/hmcts/minimal/ccd/MinimalTabs.java",
        "uk/gov/hmcts/minimal/ccd/MinimalSearch.java",
        "uk/gov/hmcts/minimal/ccd/MinimalWorkBasket.java");
    // No monolithic CoreConfig / numbered EventsConfigNN grab-bags survive.
    assertThat(tree).noneMatch(p -> p.contains("CoreConfig"));
    assertThat(tree).noneMatch(p -> p.contains("EventsConfig"));
    // Model classes stay in the model package; EnvironmentFlags lives beside CaseData.
    assertThat(tree).contains(
        "uk/gov/hmcts/minimal/model/CaseData.java",
        "uk/gov/hmcts/minimal/model/State.java",
        "uk/gov/hmcts/minimal/model/UserRole.java",
        "uk/gov/hmcts/minimal/model/EnvironmentFlags.java");

    // The event class references its page classes and uses the concise Permission set shortcut and
    // varargs roles (findings #2/#5); the page class carries the field placements.
    String createCase = Files.readString(
        srcOut.resolve("uk/gov/hmcts/minimal/ccd/event/CreateCase.java"));
    assertThat(createCase).contains("public static final String CREATE_CASE = \"createCase\"");
    assertThat(createCase).contains("builder.event(CREATE_CASE)");
    assertThat(createCase).contains("CreateCasePage1.apply(fields)");
    assertThat(createCase).contains(".grant(Permission.CRUD, UserRole.CASEWORKER_TEST)");
    assertThat(createCase).contains(".grant(Permission.CR, UserRole.CITIZEN)");

    // A single-page event inlines its one page into configure() — the page header and field chain
    // land directly on the local `fields` builder, with no page-class delegation.
    String addNotes = Files.readString(
        srcOut.resolve("uk/gov/hmcts/minimal/ccd/event/AddNotes.java"));
    assertThat(addNotes).contains("var fields = builder.event(ADD_NOTES)");
    assertThat(addNotes).contains("fields.page(");
    assertThat(addNotes).doesNotContain(".apply(fields)");
  }
}
