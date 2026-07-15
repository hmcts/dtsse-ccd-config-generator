package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.link.DefaultDefinitionLinker;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.reader.JsonDefinitionReader;

/**
 * Fast ({@code check}) unit test for the phase-2 patch emitter: it runs the matcher + linker +
 * rebinder + {@link RetrofitPatchEmitter} against the golden fake-model tree
 * ({@code retrofit/model}) — source parsing only, no compilation — and asserts the emitted unified
 * diff's content. It pins the patch's shape (per-taxonomy annotations, imports, synthesised block,
 * idempotency) without the cost of the full round-trip in {@code roundTripTest}.
 */
class RetrofitPatchEmitterGoldenTest {

  private static final Path MODEL_ROOT =
      Path.of("src/test/resources/retrofit/model/src").toAbsolutePath();
  private static final Path DEFINITION =
      Path.of("src/test/resources/retrofit/definition").toAbsolutePath();
  private static final String MODEL_PACKAGE = "uk.gov.hmcts.example.model";
  private static final String CONFIG_PACKAGE = "uk.gov.hmcts.example.config";

  private RetrofitPatchEmitter buildEmitter() {
    Map<String, OverlayCondition> overlays = new LinkedHashMap<>();
    overlays.put("prod", OverlayCondition.parse("CCD_DEF_ENV:prod"));
    overlays.put("nonprod", OverlayCondition.parse("!CCD_DEF_ENV:prod"));
    ConversionOptions options = ConversionOptions.builder()
        .inputs(java.util.List.of(DEFINITION))
        .caseTypeId("EXAMPLE")
        .modelPackage(MODEL_PACKAGE)
        .configPackage(CONFIG_PACKAGE)
        .overlaySuffixes(overlays)
        .retrofit(true)
        .retrofitCaseDataClass("CaseData")
        .build();

    DefinitionIr ir = new JsonDefinitionReader().read(options, new GapCollector());
    RetrofitMatcher matcher =
        new RetrofitMatcher(ir, "EXAMPLE", MODEL_ROOT, MODEL_PACKAGE, "CaseData");
    matcher.match();

    CaseTypeModel linked = new DefaultDefinitionLinker().link(ir, options, new GapCollector());
    RetrofitModelRebinder rebinder =
        new RetrofitModelRebinder(matcher.index(), matcher.resolution());
    CaseTypeModel rebound = rebinder.rebind(linked);

    return new RetrofitPatchEmitter(
        matcher.index(), matcher.resolution(), rebound, matcher.root(), CONFIG_PACKAGE);
  }

  private RetrofitPatch emitPatch() {
    return buildEmitter().emit();
  }

  private RetrofitPatchEmitter buildEmitter(int constructorLimit, String pathPrefix) {
    Map<String, OverlayCondition> overlays = new LinkedHashMap<>();
    overlays.put("prod", OverlayCondition.parse("CCD_DEF_ENV:prod"));
    overlays.put("nonprod", OverlayCondition.parse("!CCD_DEF_ENV:prod"));
    ConversionOptions options = ConversionOptions.builder()
        .inputs(java.util.List.of(DEFINITION))
        .caseTypeId("EXAMPLE")
        .modelPackage(MODEL_PACKAGE)
        .configPackage(CONFIG_PACKAGE)
        .overlaySuffixes(overlays)
        .retrofit(true)
        .retrofitCaseDataClass("CaseData")
        .build();
    DefinitionIr ir = new JsonDefinitionReader().read(options, new GapCollector());
    RetrofitMatcher matcher =
        new RetrofitMatcher(ir, "EXAMPLE", MODEL_ROOT, MODEL_PACKAGE, "CaseData");
    matcher.match();
    CaseTypeModel linked = new DefaultDefinitionLinker().link(ir, options, new GapCollector());
    CaseTypeModel rebound =
        new RetrofitModelRebinder(matcher.index(), matcher.resolution(), matcher.root()).rebind(linked);
    return new RetrofitPatchEmitter(matcher.index(), matcher.resolution(), rebound,
        matcher.root(), CONFIG_PACKAGE, constructorLimit, pathPrefix);
  }

  @Test
  void annotatesMatchedFieldsAndImports() {
    String diff = emitPatch().unifiedDiff();

    // Exact match gains @CCD(label); import added once.
    assertThat(diff).contains("@CCD(label = \"Applicant name\")");
    assertThat(diff).contains("+import uk.gov.hmcts.ccd.sdk.api.CCD;");
    // @JsonProperty-renamed field keeps its rename and gains @CCD.
    assertThat(diff).contains("@CCD(label = \"Renamed\")");
    // Superclass field is annotated in its own file (BaseCaseData.java).
    assertThat(diff).contains("BaseCaseData.java");
  }

  @Test
  void writesTypeOverridesForConflicts() {
    String diff = emitPatch().unifiedDiff();
    // claimType: definition FixedList over the reused model enum -> FixedList typeOverride +
    // typeParameterOverride carrying the list ID (from the linker's FixedList mapping).
    assertThat(diff).contains("typeOverride = FieldType.FixedList");
    assertThat(diff).contains("+import uk.gov.hmcts.ccd.sdk.type.FieldType;");
    // documents: concrete value-wrapper collection -> Collection typeOverride + typeParameterOverride
    // (proposal decision 8).
    assertThat(diff).contains("typeOverride = FieldType.Collection");
    assertThat(diff).contains("typeParameterOverride = \"Document\"");
  }

  @Test
  void ignoresUnmatchedJavaFieldsButNotAlreadyIgnored() {
    String diff = emitPatch().unifiedDiff();
    // orphanModelField has no definition row -> @CCD(ignore = true) is added (an added '+' line).
    assertThat(diff).contains("@CCD(ignore = true)");
    // internalCache (@JsonIgnore) and auditOnly (@CCD(ignore)) are already excluded — the patch adds
    // NO new annotation line for them (they may still appear as unchanged diff context).
    assertThat(addedLines(diff)).noneMatch(l -> l.contains("internalCache"));
    assertThat(addedLines(diff)).noneMatch(l -> l.contains("auditOnly"));
  }

  private static java.util.List<String> addedLines(String diff) {
    return diff.lines()
        .filter(l -> l.startsWith("+") && !l.startsWith("+++"))
        .collect(java.util.stream.Collectors.toList());
  }

  @Test
  void writesTypeParameterOverrideOnNestedComplexTypeCollectionMember() {
    // Bug A2 (sscs): a concrete value-wrapper collection member of a COMPLEX TYPE (Party.attachments
    // = List<DocItem>, definition FieldTypeParameter "Document") must receive the same
    // typeParameterOverride the root CaseData's `documents` field does. Before the fix the reconciler
    // ran only on root fields, so nested members got a bare label-only @CCD.
    RetrofitPatch patch = emitPatch();
    // Assert on Party's patched file directly (the @CCD is emitted on its own added line, above the
    // `private List<DocItem> attachments;` declaration — so match the patched Party source, not the
    // raw diff line-by-line).
    String partyPatched = patch.files().stream()
        .filter(f -> f.relativePath().endsWith("common/Party.java"))
        .map(RetrofitPatch.FilePatch::patchedContent)
        .findFirst()
        .orElseThrow(() -> new AssertionError("Party.java not in patch"));
    assertThat(partyPatched)
        .contains("@CCD(label = \"Attachments\", typeOverride = FieldType.Collection, "
            + "typeParameterOverride = \"Document\")")
        .contains("private List<DocItem> attachments;");
  }

  @Test
  void doesNotSynthesiseIntoBuilderBoundJsonCreatorClassAndReportsGap() {
    // Bug B3 (sscs): a complex type using the hand-written single-arg @JsonCreator + @Builder idiom
    // (Wrapper, like SSCS's Bundle/ScannedDocument) must NOT receive a synthesised field — appending
    // one breaks the builder's constructor binding. The member routes to a MANUAL_PLACEMENT gap; the
    // Wrapper file is not part of the patch at all.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    // Wrapper may still be touched (its unmatched `value` member gets @CCD(ignore=true)), but it must
    // NOT gain a synthesised field — that is the builder-breaking edit.
    patch.files().stream()
        .filter(f -> f.relativePath().endsWith("common/Wrapper.java"))
        .forEach(f -> assertThat(f.patchedContent())
            .doesNotContain("synthesised definition-only fields")
            .doesNotContain("newDetail"));
    assertThat(emitter.gaps())
        .anySatisfy(g -> {
          assertThat(g.getRowKey()).isEqualTo("Wrapper/newDetail");
          assertThat(g.getAction())
              .isEqualTo(uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction.MANUAL_PLACEMENT);
          assertThat(g.getDetail()).contains("@JsonCreator").contains("@Builder");
        });
  }

  @Test
  void doesNotSynthesiseIntoClassWithSubclassPositionalSuperCallAndReportsGap() {
    // Bug B4 (civil): RecoverableCosts is @AllArgsConstructor and its subclass RecoverableCostsSection
    // calls super(band, reasons) positionally. Synthesising `bandLabel` would widen the all-args
    // constructor to 3 args and break that fixed-arity super call, so the member must route to a
    // MANUAL_PLACEMENT gap and RecoverableCosts must not gain a synthesised field.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    patch.files().stream()
        .filter(f -> f.relativePath().endsWith("common/RecoverableCosts.java"))
        .forEach(f -> assertThat(f.patchedContent())
            .doesNotContain("synthesised definition-only fields")
            .doesNotContain("bandLabel"));
    assertThat(emitter.gaps())
        .anySatisfy(g -> {
          assertThat(g.getRowKey()).isEqualTo("RecoverableCosts/bandLabel");
          assertThat(g.getAction())
              .isEqualTo(uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction.MANUAL_PLACEMENT);
          assertThat(g.getDetail()).contains("super(");
        });
  }

  @Test
  void doesNotSynthesiseIntoValueClassWithHandwrittenConstructorAndReportsGap() {
    // The @Value/final-field guard (civil's Bundle): ValueHolder is @Value (final fields) with a
    // hand-written @JsonCreator that assigns only `held`. A synthesised final field would be left
    // uninitialised, so `stitchStatus` must route to a MANUAL_PLACEMENT gap and ValueHolder must not
    // gain a synthesised field.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    patch.files().stream()
        .filter(f -> f.relativePath().endsWith("common/ValueHolder.java"))
        .forEach(f -> assertThat(f.patchedContent())
            .doesNotContain("synthesised definition-only fields")
            .doesNotContain("stitchStatus"));
    assertThat(emitter.gaps())
        .anySatisfy(g -> {
          assertThat(g.getRowKey()).isEqualTo("ValueHolder/stitchStatus");
          assertThat(g.getAction())
              .isEqualTo(uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction.MANUAL_PLACEMENT);
          assertThat(g.getDetail()).contains("@Value").contains("final");
        });
  }

  @Test
  void synthesisesDefinitionOnlyFieldInDelimitedBlock() {
    String diff = emitPatch().unifiedDiff();
    assertThat(diff).contains("synthesised definition-only fields");
    assertThat(diff).contains("private String extraSynthField;");
  }

  @Test
  void skipsSynthesisingAFieldThatCollidesWithAnExistingModelMember() {
    // Bug B1 (prl): a definition-only field whose synthesised member name equals an existing declared
    // field (here `confidentialData`, the model's @JsonUnwrapped parent — never a resolved leaf, so
    // it looks "unmatched") must NOT be re-declared (that is prl's `variable X is already defined`
    // compile break). It is skipped and a gap is recorded; the un-colliding `extraSynthField` is
    // still synthesised.
    RetrofitPatchEmitter emitter = buildEmitter();
    String diff = emitter.emit().unifiedDiff();
    assertThat(addedLines(diff)).noneMatch(l -> l.contains("private ") && l.contains("confidentialData;"));
    assertThat(diff).contains("private String extraSynthField;");
    assertThat(emitter.gaps())
        .anySatisfy(g -> {
          assertThat(g.getRowKey()).isEqualTo("confidentialData");
          assertThat(g.getDetail()).contains("already declares a field");
        });
  }

  @Test
  void doesNotReAnnotateFieldsAlreadyCarryingCcd() {
    // The golden model's auditOnly carries @CCD(ignore = true); it must never be re-annotated (the
    // patch adds no new line mentioning it).
    String diff = emitPatch().unifiedDiff();
    assertThat(addedLines(diff)).noneMatch(l -> l.contains("auditOnly"));
  }

  @Test
  void rootsPatchPathsAtTheModelRepoRootWhenPrefixed() {
    // Patch-root consistency: with a source-root-relative-to-repo prefix, every emitted diff path
    // (and the added CaseDataExtra new file) is rooted at the repo root, so bin/retrofit-verify
    // applies every lane's patch the same way.
    RetrofitPatch patch = buildEmitter(0, "service/src/main/java").emit();
    assertThat(patch.files())
        .allSatisfy(f -> assertThat(f.relativePath()).startsWith("service/src/main/java/"));
    assertThat(patch.unifiedDiff())
        .contains("a/service/src/main/java/uk/gov/hmcts/example/model/CaseData.java")
        .contains("b/service/src/main/java/uk/gov/hmcts/example/model/CaseData.java");
  }

  @Test
  void defaultsToSourceRootRelativePathsWithNoPrefix() {
    RetrofitPatch patch = buildEmitter(0, "").emit();
    assertThat(patch.files())
        .anySatisfy(f -> assertThat(f.relativePath())
            .isEqualTo("uk/gov/hmcts/example/model/CaseData.java"));
  }

  @Test
  void perFilePatchesExposeBeforeAndAfter() {
    RetrofitPatch patch = emitPatch();
    assertThat(patch.files()).isNotEmpty();
    assertThat(patch.files())
        .allSatisfy(f -> {
          assertThat(f.relativePath()).endsWith(".java");
          assertThat(f.originalContent()).isNotEqualTo(f.patchedContent());
        });
  }

  @Test
  void addedAnnotationLandsOnItsOwnLineLeavingAFieldsExistingAnnotationAsPureContext() {
    // Annotation-placement fix: someInternalName already carries @JsonProperty("renamedId") (golden
    // model's rule 2). The added @CCD must land on its OWN line above it — the pre-existing
    // @JsonProperty line must appear in the diff as unchanged context (a ' ' line), never as part of
    // an added/changed line (the "one long line, existing annotation looks modified" defect).
    String diff = emitPatch().unifiedDiff();
    java.util.List<String> lines = diff.lines().collect(java.util.stream.Collectors.toList());
    // A unified-diff context line is a single leading space plus the original (unindented-by-diff)
    // source line — the golden model indents someInternalName's @JsonProperty by two spaces.
    int jsonPropertyLine = lines.indexOf("   @JsonProperty(\"renamedId\")");
    assertThat(jsonPropertyLine).isGreaterThanOrEqualTo(0);
    // A context line in a unified diff starts with a single space, never '+' or '-'.
    assertThat(lines.get(jsonPropertyLine)).startsWith(" ").doesNotStartWith(" +").doesNotStartWith(" -");
    // The added @CCD for the same field is its own '+' line, immediately preceding the context line.
    assertThat(lines.get(jsonPropertyLine - 1)).startsWith("+").contains("@CCD(label = \"Renamed\")");
    // No line joins the two annotations (the exact defect: "...) @CCD(...)" on one line).
    assertThat(addedLines(diff)).noneMatch(l -> l.contains(") @CCD(") || l.contains(")@CCD("));
  }

  @Test
  void noEmittedLineExceedsTheHouseCheckstyleLineLimit() {
    // Annotation-placement fix: wrapping kicks in before any added line crosses the 120-column
    // limit every retrofitted team's checkstyle enforces.
    String diff = emitPatch().unifiedDiff();
    assertThat(addedLines(diff))
        .allSatisfy(l -> assertThat(l.length() - 1)
            .describedAs("added line (minus the leading '+') exceeds 120 columns: %s", l)
            .isLessThanOrEqualTo(120));
  }

  @Test
  void doesNotMisplaceTheNoNewlineMarkerWhenTheLastHunkDoesNotReachEof() {
    // Regression (unrelated pre-existing bug found while fixing annotation placement):
    // NoTrailingNewlineHost.java has NO trailing newline, and its one annotated member
    // (orphanField -> @CCD(ignore = true)) is followed by four more unchanged methods before the
    // file's true final line — more than the diff's 3-line trailing context window. The emitter
    // must NOT stamp "\ No newline at end of file" onto that hunk's last printed (context) line: it
    // is not the file's actual last line, and git apply would wrongly concatenate it with whatever
    // source line follows.
    RetrofitPatch patch = emitPatch();
    String hostPatched = patch.files().stream()
        .filter(f -> f.relativePath().endsWith("common/NoTrailingNewlineHost.java"))
        .map(RetrofitPatch.FilePatch::patchedContent)
        .findFirst()
        .orElseThrow(() -> new AssertionError("NoTrailingNewlineHost.java not in patch"));
    assertThat(hostPatched).doesNotEndWith("\n");
    String diff = patch.unifiedDiff();
    java.util.List<String> lines = diff.lines().collect(java.util.stream.Collectors.toList());
    int hunkStart = lines.indexOf("+++ b/uk/gov/hmcts/example/model/common/NoTrailingNewlineHost.java");
    assertThat(hunkStart).isGreaterThanOrEqualTo(0);
    int nextFileHeader = lines.subList(hunkStart + 1, lines.size()).indexOf("--- a/uk/gov/hmcts/example/model/common/Party.java");
    java.util.List<String> hostHunkLines = nextFileHeader < 0
        ? lines.subList(hunkStart, lines.size())
        : lines.subList(hunkStart, hunkStart + 1 + nextFileHeader);
    assertThat(hostHunkLines).noneMatch(l -> l.contains("No newline at end of file"));
  }
}
