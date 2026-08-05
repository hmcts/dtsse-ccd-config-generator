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
  void addressesCollectionElementWrapperMembersOnItsValueClass() {
    // Bug (sscs): a definition complex type whose model class is a hand-rolled {id, value} collection
    // -element wrapper (Wrapper, like SSCS's Bundle/ScannedDocument) has its ComplexTypes rows
    // describing the VALUE class (WrapperDetails), because CCD roots a collection element's member
    // namespace at `value`. The patch must annotate/synthesise onto WrapperDetails, NOT onto the
    // wrapper — targeting the wrapper made every member look definition-only and refused them all as
    // builder-binding breaks (111 members across 22 sscs classes reported as "add the field by hand"
    // when the fields already existed on the *Details class).
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();

    String detailsPatched = patch.files().stream()
        .filter(f -> f.relativePath().endsWith("common/WrapperDetails.java"))
        .map(RetrofitPatch.FilePatch::patchedContent)
        .findFirst()
        .orElseThrow(() -> new AssertionError("WrapperDetails.java not in patch"));
    // The existing member is annotated in place (not synthesised), and only the genuinely
    // definition-only member is added.
    assertThat(detailsPatched)
        .contains("@CCD(label = \"A member the definition addresses on the wrapper's value class\")")
        .contains("synthesised definition-only fields")
        .contains("private String newDetail;");

    // The wrapper itself gains no synthesised field and no widened constructor.
    patch.files().stream()
        .filter(f -> f.relativePath().endsWith("common/Wrapper.java"))
        .forEach(f -> assertThat(f.patchedContent())
            .doesNotContain("synthesised definition-only fields")
            .doesNotContain("newDetail"));
    // And nothing is routed to a manual-placement gap: the members are all placed.
    assertThat(emitter.gaps())
        .noneMatch(g -> g.getRowKey() != null && g.getRowKey().startsWith("Wrapper/"));
  }

  @Test
  void widensBuilderBoundJsonCreatorConstructorForSynthesisedMember() {
    // A @Data @Builder complex type whose builder Lombok binds to a hand-written multi-arg
    // @JsonCreator constructor (sscs's Appeal shape) is NOT refused: the patch synthesises the
    // definition-only member AND widens the bound constructor to take it, so the generated builder
    // still calls a constructor of matching arity. Verified against Lombok 1.18.38 — an extended
    // @JsonCreator constructor compiles and both builder() and toBuilder() set the added field.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    String patched = patch.files().stream()
        .filter(f -> f.relativePath().endsWith("common/BuilderBoundParty.java"))
        .map(RetrofitPatch.FilePatch::patchedContent)
        .findFirst()
        .orElseThrow(() -> new AssertionError("BuilderBoundParty.java not in patch"));

    assertThat(patched)
        .contains("synthesised definition-only fields")
        .contains("private String panelComposition;")
        // The parameter is appended with its CCD id as @JsonProperty, keeping the team's
        // one-parameter-per-line shape.
        .contains("@JsonProperty(\"benefitType\") String benefitType,")
        .contains("@JsonProperty(\"panelComposition\") String panelComposition)")
        // …and assigned in the body, so the field is actually initialised.
        .contains("this.panelComposition = panelComposition;")
        // …and the original 2-arg signature survives as a delegating overload, so callers outside the
        // parsed source (sscs-common is a published library) still compile.
        .contains("public BuilderBoundParty(String appellantName, String benefitType) {")
        .contains("this(appellantName, benefitType, null);");
    // The existing assignments are untouched.
    assertThat(patched).contains("this.benefitType = benefitType;");
    assertThat(emitter.gaps())
        .noneMatch(g -> "BuilderBoundParty/panelComposition".equals(g.getRowKey()));
  }

  @Test
  void refusesWideningWhenANarrowOverloadWouldCollideWithAnotherConstructor() {
    // Two hand-written non-delegating constructors differing by one String. Widening both makes the
    // SHORTER one's widened form (String, String) occupy exactly the signature the LONGER one's narrow
    // overload needs. Emitting the patch anyway is not an option in either direction: keeping both
    // overloads declares the same constructor twice (does not compile), and suppressing one silently
    // rebinds every existing `new TwoConstructorParty(a, b)` to the widened 1-arg constructor, which
    // would assign b to the synthesised field instead of `secondary`. So the class is refused whole and
    // the member routed to a manual-placement gap.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    patch.files().stream()
        .filter(f -> f.relativePath().endsWith("common/TwoConstructorParty.java"))
        .forEach(f -> assertThat(f.patchedContent())
            .doesNotContain("synthesised definition-only fields")
            .doesNotContain("tertiary")
            .doesNotContain("Retained so existing positional call sites"));
    assertThat(emitter.gaps())
        .anySatisfy(g -> {
          assertThat(g.getRowKey()).isEqualTo("TwoConstructorParty/tertiary");
          assertThat(g.getAction())
              .isEqualTo(uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction.MANUAL_PLACEMENT);
          assertThat(g.getDetail()).contains("delegating overload");
        });
  }

  @Test
  void addsNarrowAllArgsConstructorSoASubclassPositionalSuperCallStillBinds() {
    // Bug B4 (civil's FixedRecoverableCosts): RecoverableCosts is @AllArgsConstructor and its subclass
    // RecoverableCostsSection calls super(band, reasons) positionally. Synthesising `bandLabel` widens
    // the GENERATED all-args constructor to 3 args, so the patch adds an explicit narrow constructor
    // over the pre-synthesis field list delegating this(band, reasons, null) — the subclass binds to
    // that and needs no edit of its own.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    String patched = patch.files().stream()
        .filter(f -> f.relativePath().endsWith("common/RecoverableCosts.java"))
        .map(RetrofitPatch.FilePatch::patchedContent)
        .findFirst()
        .orElseThrow();
    assertThat(patched)
        .contains("private String bandLabel;")
        .contains("/** Retained so a subclass's positional super(...) call still binds. */")
        .contains("public RecoverableCosts(String band, String reasons) {")
        .contains("this(band, reasons, null);");
    // The subclass itself must be untouched — that is what also protects subclasses outside the
    // parsed source tree (a published model jar's consumers).
    assertThat(patch.files())
        .noneMatch(f -> f.relativePath().endsWith("common/RecoverableCostsSection.java"));
    assertThat(emitter.gaps())
        .noneSatisfy(g -> assertThat(g.getRowKey()).isEqualTo("RecoverableCosts/bandLabel"));
  }

  @Test
  void refusesNarrowAllArgsConstructorWhenTheAllArgsFormIsInferredFromBuilder() {
    // BuilderOnlyCosts is @Data @Builder with NO explicit @AllArgsConstructor, so Lombok INFERS its
    // all-args constructor — and only while the class declares no constructor at all. Adding the narrow
    // constructor that would bind BuilderOnlyCostsSection's super(cap, note) suppresses that inference
    // and the generated builder stops compiling (verified against Lombok 1.18.38), so this shape must
    // stay a MANUAL_PLACEMENT gap.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    patch.files().stream()
        .filter(f -> f.relativePath().endsWith("common/BuilderOnlyCosts.java"))
        .forEach(f -> assertThat(f.patchedContent())
            .doesNotContain("synthesised definition-only fields")
            .doesNotContain("capLabel")
            .doesNotContain("Retained so a subclass's positional super(...) call still binds"));
    assertThat(emitter.gaps())
        .anySatisfy(g -> {
          assertThat(g.getRowKey()).isEqualTo("BuilderOnlyCosts/capLabel");
          assertThat(g.getAction())
              .isEqualTo(uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction.MANUAL_PLACEMENT);
          assertThat(g.getDetail()).contains("INFERRED all-args constructor");
        });
  }

  @Test
  void widensValueClassConstructorSoTheSynthesisedFinalFieldIsInitialised() {
    // civil's Bundle shape: ValueHolder is @Value (Lombok makes EVERY field private final) with a
    // hand-written single-line @JsonCreator that assigns only `held`. The synthesised field is final
    // too, so the constructor is widened to initialise it rather than the member being refused. The
    // parameter list was written on one line, so it stays on one line.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    String patched = patch.files().stream()
        .filter(f -> f.relativePath().endsWith("common/ValueHolder.java"))
        .map(RetrofitPatch.FilePatch::patchedContent)
        .findFirst()
        .orElseThrow(() -> new AssertionError("ValueHolder.java not in patch"));

    assertThat(patched)
        .contains("synthesised definition-only fields")
        .contains("private String stitchStatus;")
        .contains("public ValueHolder(@JsonProperty(\"held\") String held, "
            + "@JsonProperty(\"stitchStatus\") String stitchStatus) {")
        .contains("this.stitchStatus = stitchStatus;")
        .contains("this.held = held;")
        // The narrow delegating overload keeps `new ValueHolder(held)` call sites compiling. It is
        // unannotated: @JsonCreator stays on the widened constructor alone so Jackson and Lombok's
        // @Builder both bind there.
        .contains("/** Retained so existing positional call sites still compile. */")
        .contains("public ValueHolder(String held) {")
        .contains("this(held, null);");
    assertThat(emitter.gaps())
        .noneMatch(g -> "ValueHolder/stitchStatus".equals(g.getRowKey()));
  }

  @Test
  void synthesisesIntoDataClassWithFinalFieldsAndConstructorLevelBuilder() {
    // Defect 2 (fpl RespondentParty/ChildParty): FinalFieldParty is @Data with a private-final field
    // and a constructor-level @Builder. A definition-only member (synthLabel) MUST be synthesised onto
    // it as a NON-final field (which compiles and is set via the Lombok setter) — the over-broad
    // "any final field" guard used to reject this shape and drop the member to a gap.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    String finalFieldPartyPatched = patch.files().stream()
        .filter(f -> f.relativePath().endsWith("common/FinalFieldParty.java"))
        .map(RetrofitPatch.FilePatch::patchedContent)
        .findFirst()
        .orElseThrow(() -> new AssertionError("FinalFieldParty.java not in patch"));
    assertThat(finalFieldPartyPatched)
        .contains("synthesised definition-only fields")
        .contains("private String synthLabel;");
    // It must NOT be routed to a @Value/final-field manual-placement gap.
    assertThat(emitter.gaps())
        .noneMatch(g -> "FinalFieldParty/synthLabel".equals(g.getRowKey()));
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
