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
    return buildEmitter(RetrofitPinnedNames.empty());
  }

  /**
   * The same wiring, with the naming-strategy names the {@code CaseEventToComplexTypes} member walk
   * relied on — which the patch must pin as explicit {@code @JsonProperty} annotations.
   */
  private RetrofitPatchEmitter buildEmitter(RetrofitPinnedNames pinnedNames) {
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

    return new RetrofitPatchEmitter(matcher.index(), matcher.resolution(), rebound, matcher.root(),
        CONFIG_PACKAGE, 0, "", pinnedNames);
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

  private RetrofitPatch emitPatch() {
    return buildEmitter().emit();
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
  void pinsTheDefinitionsOwnComplexTypeIdOntoTheClassThatBacksIt() {
    // Binding a definition complex type to a model class fixes that type's MEMBERS but not its ID:
    // ComplexTypeGenerator names the emitted type c.getSimpleName() unless the class carries
    // @ComplexType(name). CCD ComplexTypes IDs are overwhelmingly camelCase (108 of sscs's 118) while
    // model classes are PascalCase — the very divergence ModelSourceIndex.complexTypeClass's
    // case-insensitive fallback exists to bridge — so every bound class emitted its type under an ID the
    // definition never mentions. generate = true is mandatory: the attribute DEFAULTS to false and
    // ComplexTypeGenerator skips a named-but-not-generate type entirely, so a bare name would trade a
    // wrong ID for no rows at all.
    String patched = patchedFile(emitPatch(), "common/NoticeDetails.java");
    assertThat(patched)
        .contains("@ComplexType(name = \"noticeDetails\", generate = true)")
        .contains("import uk.gov.hmcts.ccd.sdk.api.ComplexType;");
  }

  @Test
  void suppressesTheImplicitCollectionElementWrapperWithGenerateFalse() {
    // The second half of the ID pin. CCD serialises collection elements as {id, value}, so a definition
    // addresses a collection element type's members on its VALUE class — but ConfigResolver.resolve
    // registers a NON-GENERIC wrapper as a complex type in its own right (unlike List<ListValue<X>>,
    // whose generic element it descends through), emitting a spurious {value}/{id,value} row. sscs had 36
    // such wrapper-shaped generated types against exactly ONE wrapper-shaped definition type.
    // @ComplexType(name = <id>, generate = false) suppresses the row while `name` still gives
    // CaseFieldGenerator.resolveCollectionType the right FieldTypeParameter for every List<Wrapper> field
    // — which is why the wrapper is named at all rather than simply ignored.
    RetrofitPatch patch = emitPatch();
    assertThat(patchedFile(patch, "common/Wrapper.java"))
        .contains("@ComplexType(name = \"Wrapper\", generate = false)");
    // Its VALUE class carries the generate = true half, so the type's rows come from the class the
    // definition's member rows actually describe.
    assertThat(patchedFile(patch, "common/WrapperDetails.java"))
        .contains("@ComplexType(name = \"Wrapper\", generate = true)");
  }

  @Test
  void refusesToPinOverAComplexTypeAnnotationTheClassAlreadyCarries() {
    // Idempotency, and the team's own choices: PinnedByTeamCT already declares
    // @ComplexType(name = "teamsOwnChoice"), so the definition ID pinnedByTeamCT must NOT be pinned — a
    // second class-level @ComplexType would not even compile, and overwriting the team's name/label/
    // border is not the patch's call. The same guard makes re-applying the patch a no-op.
    //
    // NOTE ON THE FIXTURE: PinnedByTeamCT is named to match the definition ID pinnedByTeamCT
    // case-insensitively, because that is the only way complexTypeClass binds an ID to a class — a
    // PascalCase name unrelated to the ID never reaches the pin at all, so the refusal would be
    // untested (verified: with the guard removed the test still passed until the class was renamed).
    // The file IS still patched — its `note` member gains the definition's @CCD(label) like any other
    // matched member. Only the CLASS-LEVEL pin is refused, so assert on that: exactly the one
    // @ComplexType the team wrote, and no added line carrying the definition ID.
    RetrofitPatch patch = emitPatch();
    assertThat(complexTypeAnnotations(patchedFile(patch, "common/PinnedByTeamCT.java")))
        .containsExactly("@ComplexType(name = \"teamsOwnChoice\", generate = true)");
    assertThat(addedLines(patch.unifiedDiff())).noneMatch(l -> l.contains("pinnedByTeamCT"));
  }

  /**
   * The class-level {@code @ComplexType} annotation lines in a patched file — matched as whole
   * declaration lines, so a mention of the annotation in the class's own javadoc is not counted.
   */
  private static java.util.List<String> complexTypeAnnotations(String patched) {
    return patched.lines()
        .map(String::trim)
        .filter(l -> l.startsWith("@ComplexType("))
        .toList();
  }

  @Test
  void reportsOneClassBackingTwoDefinitionComplexTypesRatherThanPickingSilently() {
    // sscs's real shape: ten dwp*DocumentCT types plus tl1FormCT and appendix12DocumentCT are declared
    // separately in the definition but modelled by one class. A class carries only ONE
    // @ComplexType(name), so the second definition type to reach it cannot be pinned. Picking silently
    // would leave the loser emitting under the winner's ID with no trace; the collision is reported as a
    // MANUAL_PLACEMENT gap instead, so the team sees the one thing only they can decide — split the class
    // or add a per-field type override.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    // Exactly one of the two IDs wins on the shared payload class, never both.
    assertThat(complexTypeAnnotations(patchedFile(patch, "common/SharedDetails.java")))
        .containsExactly("@ComplexType(name = \"firstSharedCT\", generate = true)");
    // Each WRAPPER is a distinct class, so both are still suppressed — the collision is on the value
    // class alone.
    assertThat(patchedFile(patch, "common/FirstSharedCT.java"))
        .contains("@ComplexType(name = \"firstSharedCT\", generate = false)");
    assertThat(patchedFile(patch, "common/SecondSharedCT.java"))
        .contains("@ComplexType(name = \"secondSharedCT\", generate = false)");
    // The loser is reported, naming both types and the class they share.
    assertThat(emitter.gaps())
        .anySatisfy(g -> {
          assertThat(g.getSheet()).isEqualTo("ComplexTypes");
          assertThat(g.getRowKey()).isEqualTo("secondSharedCT");
          assertThat(g.getAction())
              .isEqualTo(uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction.MANUAL_PLACEMENT);
          assertThat(g.getDetail())
              .contains("firstSharedCT")
              .contains("secondSharedCT")
              .contains("SharedDetails");
        });
  }

  @Test
  void retypesAFieldToTheGeneratedCompanionWhenItsDefinitionTypeHasNoModelClass() {
    // firstSummaryCT/secondSummaryCT have no model class, so the converter generates a companion for
    // each — but Party declares BOTH members as one shared SharedSummary (sscs's ten dwp*DocumentCT /
    // one DwpResponseDocument shape). Nothing pointed the fields at the companions, so the SDK emitted
    // FieldType=SharedSummary and the definition's own rows had no counterpart. Re-declaring each field
    // as its own companion is the only binding that works here: a class carries one @ComplexType(name),
    // and typeOverride takes a FieldType enum constant, which a definition ID is not.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    String patched = patchedFile(patch, "common/Party.java");
    assertThat(patched)
        .contains("private FirstSummaryCT firstSummary;")
        .contains("private SecondSummaryCT secondSummary;")
        // The shared class is left exactly as it was — the retype needs no pin on it.
        .doesNotContain("private SharedSummary firstSummary;")
        .doesNotContain("private SharedSummary secondSummary;");
    // The companions are generated into the model package, which is Party's OWN package's parent, so
    // the retype adds the import it needs.
    assertThat(patched).contains("import " + MODEL_PACKAGE + ".FirstSummaryCT;");
  }

  @Test
  void refusesToRetypeAFieldWhoseAccessorsTheModelCallsAndReportsIt() {
    // SummaryReader assigns party.getReadSummary() to a SharedSummary. Re-declaring the field changes
    // what that getter returns, so the call stops compiling — the patch must leave the declaration alone
    // and report the row rather than emit a break. Matched by method NAME alone (the index has no symbol
    // solver), which is deliberately conservative.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    assertThat(patchedFile(patch, "common/Party.java"))
        .contains("private SharedSummary readSummary;")
        .doesNotContain("ReadSummaryCT readSummary;");
    assertThat(emitter.gaps())
        .anySatisfy(g -> {
          assertThat(g.getSheet()).isEqualTo("ComplexTypes");
          assertThat(g.getRowKey()).isEqualTo("Party/readSummary");
          assertThat(g.getAction())
              .isEqualTo(uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction.MANUAL_PLACEMENT);
          assertThat(g.getDetail())
              .contains("readSummaryCT")
              .contains("get/setReadSummary")
              .contains("SharedSummary");
        });
  }

  @Test
  void refusesToRetypeAFieldTheDeclaringClassReadsDirectlyAndReportsIt() {
    // Party.resolveInlineSummary() returns inlineReadSummary as a SharedSummary with no accessor in
    // between (fpl's CaseData.getOrders() shape, which returned the retyped ordersSolicitor as an
    // Orders). The accessor check cannot see this — there is no get/set call — so the refusal must also
    // look for the field read as a bare identifier inside its own declaring source.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    assertThat(patchedFile(patch, "common/Party.java"))
        .contains("private SharedSummary inlineReadSummary;")
        .doesNotContain("InlineReadSummaryCT inlineReadSummary;");
    assertThat(emitter.gaps())
        .anySatisfy(g -> {
          assertThat(g.getSheet()).isEqualTo("ComplexTypes");
          assertThat(g.getRowKey()).isEqualTo("Party/inlineReadSummary");
          assertThat(g.getAction())
              .isEqualTo(uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction.MANUAL_PLACEMENT);
          assertThat(g.getDetail())
              .contains("inlineReadSummaryCT")
              .contains("read directly by hand-written code in Party")
              .contains("SharedSummary");
        });
  }

  @Test
  void refusesToRetypeAFieldSetThroughABuilderMethodNamedAfterItAndReportsIt() {
    // SummaryReader.build() calls Party.builder().builderSetSummary(summary) — a Lombok @Builder setter
    // named after the FIELD, with no get/set prefix for the accessor check to match (fpl's
    // .respondents(respondentsInCase) shape). The retype changes that parameter's type.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    assertThat(patchedFile(patch, "common/Party.java"))
        .contains("private SharedSummary builderSetSummary;")
        .doesNotContain("BuilderSetSummaryCT builderSetSummary;");
    assertThat(emitter.gaps())
        .anySatisfy(g -> {
          assertThat(g.getSheet()).isEqualTo("ComplexTypes");
          assertThat(g.getRowKey()).isEqualTo("Party/builderSetSummary");
          assertThat(g.getAction())
              .isEqualTo(uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction.MANUAL_PLACEMENT);
          assertThat(g.getDetail())
              .contains("builderSetSummaryCT")
              .contains(".builderSetSummary(…)")
              .contains("SharedSummary");
        });
  }

  @Test
  void refusesToRetypeAFieldShadowedElsewhereInItsHierarchyAndReportsIt() {
    // ShadowBase/ShadowChild declare the same two field names, so Lombok generates an overriding
    // accessor pair per declaration (ET's BaseCaseData/CaseData shape). Retyping either declaration
    // alone breaks the override, so both directions must refuse: the definition addresses
    // baseAddressedSummary on the SUPERCLASS (shadowed by a descendant) and childAddressedSummary on
    // the SUBCLASS (shadowed by an ancestor).
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    assertThat(patchedFile(patch, "common/ShadowBase.java"))
        .contains("private SharedSummary baseAddressedSummary;")
        .doesNotContain("BaseShadowSummaryCT");
    assertThat(patchedFile(patch, "common/ShadowChild.java"))
        .contains("private SharedSummary childAddressedSummary;")
        .doesNotContain("ChildShadowSummaryCT");
    assertThat(emitter.gaps())
        .anySatisfy(g -> {
          assertThat(g.getRowKey()).isEqualTo("ShadowBase/baseAddressedSummary");
          assertThat(g.getDetail())
              .contains("declared on both ShadowBase and ShadowChild");
        })
        .anySatisfy(g -> {
          assertThat(g.getRowKey()).isEqualTo("ShadowChild/childAddressedSummary");
          assertThat(g.getDetail())
              .contains("declared on both ShadowChild and ShadowBase");
        });
  }

  @Test
  void stillRetypesAFieldNothingReadsDirectly() {
    // The counterpart to the two refusals above: firstSummary/secondSummary are read by nothing at all,
    // so the new checks must not widen into a blanket refusal. Pinned separately from the retype test
    // so a regression here names the cause.
    RetrofitPatch patch = emitPatch();
    assertThat(patchedFile(patch, "common/Party.java"))
        .contains("private FirstSummaryCT firstSummary;")
        .contains("private SecondSummaryCT secondSummary;");
  }

  /** The patched content of one file in the patch, failing clearly when the patch does not touch it. */
  private static String patchedFile(RetrofitPatch patch, String pathSuffix) {
    return patch.files().stream()
        .filter(f -> f.relativePath().endsWith(pathSuffix))
        .map(RetrofitPatch.FilePatch::patchedContent)
        .findFirst()
        .orElseThrow(() -> new AssertionError(pathSuffix + " not in patch"));
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
  void marksSynthesisedFieldsNonNullOnClassesThatSerialiseNulls() {
    // sscs Appellant/Appointee/Representative/OverrideFields: a class-level @JsonInclude with NO
    // value means ALWAYS, so a synthesised definition-only field — which the team's code never
    // populates — would add "<id>": null to every serialised instance. On a published library that
    // is a breaking wire-format change (it surfaced as `Expected: pcqId but none found` in sscs's own
    // should_deserialise_and_serialise). Each synthesised field therefore carries its own
    // @JsonInclude(NON_NULL), and the file gains the import. The CCD definition is unaffected: the
    // SDK derives CaseField rows from FIELDS and reads no Jackson inclusion setting.
    RetrofitPatch patch = emitPatch();
    String alwaysPatched = patchedContent(patch, "common/AlwaysIncludedParty.java");
    assertThat(alwaysPatched)
        .contains("synthesised definition-only fields")
        .contains("@JsonInclude(JsonInclude.Include.NON_NULL)")
        .contains("private String alwaysSynthField;")
        .contains("import com.fasterxml.jackson.annotation.JsonInclude;");
  }

  @Test
  void leavesSynthesisedFieldsUnannotatedWhenTheClassAlreadySuppressesNulls() {
    // The complement: a VALUED @JsonInclude(NON_NULL) already suppresses nulls class-wide, so the
    // per-field annotation would be redundant noise. Pins that the fix keys on the MARKER form alone
    // rather than annotating every synthesis site.
    RetrofitPatch patch = emitPatch();
    RetrofitPatch.FilePatch file = filePatch(patch, "common/NonNullIncludedParty.java");
    assertThat(file.patchedContent())
        .contains("synthesised definition-only fields")
        .contains("private String nonNullSynthField;");
    // Asserted over ADDED lines only: the class's own declaration is literally
    // `@JsonInclude(JsonInclude.Include.NON_NULL)`, so the whole-file text cannot distinguish.
    assertThat(addedLines(file)).noneMatch(l -> l.contains("@JsonInclude"));
  }

  private static String patchedContent(RetrofitPatch patch, String pathSuffix) {
    return filePatch(patch, pathSuffix).patchedContent();
  }

  private static RetrofitPatch.FilePatch filePatch(RetrofitPatch patch, String pathSuffix) {
    return patch.files().stream()
        .filter(f -> f.relativePath().endsWith(pathSuffix))
        .findFirst()
        .orElseThrow(() -> new AssertionError(pathSuffix + " not in patch"));
  }

  /** The lines this file patch adds — the original's own lines are excluded. */
  private static java.util.List<String> addedLines(RetrofitPatch.FilePatch file) {
    java.util.Set<String> original = file.originalContent().lines()
        .map(String::trim)
        .collect(java.util.stream.Collectors.toSet());
    return file.patchedContent().lines()
        .filter(l -> !original.contains(l.trim()))
        .collect(java.util.stream.Collectors.toList());
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
  void plansExactlyTheSynthesisedMembersItCommitsToAdding() {
    // The CaseEventToComplexTypes member walk is built against the model as the applied PATCH will
    // leave it, so it reads this plan (RetrofitPlannedSynthesis) to resolve a member the patch is about
    // to add. The plan must therefore agree with the patch EXACTLY: every member reported here has to be
    // one the emitter really adds, or the graph would emit a getter reference to a field that never
    // exists. It comes from the emitter's own planning pass, not a re-derivation, so the refusals below
    // are excluded by construction.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPlannedSynthesis planned = emitter.planSynthesisedMembers();

    // Members the emitter commits to (each pinned by its own test above): the narrow-all-args repair,
    // the widened @JsonCreator, the widened @Value constructor, the final-field @Data class, and the
    // collection-element wrapper's VALUE class.
    assertThat(planned.member("uk.gov.hmcts.example.model.common.RecoverableCosts", "bandLabel"))
        .get()
        .satisfies(m -> assertThat(m.javaName()).isEqualTo("bandLabel"));
    assertThat(planned.member(
        "uk.gov.hmcts.example.model.common.BuilderBoundParty", "panelComposition")).isPresent();
    assertThat(planned.member("uk.gov.hmcts.example.model.common.ValueHolder", "stitchStatus"))
        .isPresent();
    assertThat(planned.member("uk.gov.hmcts.example.model.common.FinalFieldParty", "synthLabel"))
        .isPresent();
    assertThat(planned.member("uk.gov.hmcts.example.model.common.WrapperDetails", "newDetail"))
        .isPresent();

    // Members the emitter REFUSES — the inferred-all-args guard and the overload-collision guard both
    // route theirs to a MANUAL_PLACEMENT gap, so neither may appear.
    assertThat(planned.member("uk.gov.hmcts.example.model.common.BuilderOnlyCosts", "capLabel"))
        .isEmpty();
    assertThat(planned.member("uk.gov.hmcts.example.model.common.TwoConstructorParty", "tertiary"))
        .isEmpty();
    // The wrapper class itself is never a synthesis target — its members belong to the value class.
    assertThat(planned.member("uk.gov.hmcts.example.model.common.Wrapper", "newDetail")).isEmpty();
  }

  @Test
  void pinsExactlyTheNamingStrategyNamesTheMemberWalkReliedOn() {
    // The mirror of the plan above, in the opposite direction: there the PATCH decides and the graph
    // follows; here the GRAPH decides (its CaseEventToComplexTypes walk resolved a member under an id
    // the SDK would not derive) and the patch must follow by pinning that id as an explicit
    // @JsonProperty. The SDK reads @JsonProperty only off the field and the read method, so resolving
    // WITHOUT pinning would emit NamedAddress::getAddressLine1 and have the SDK regenerate the id
    // 'addressLine1' — silently changing the CCD field id. The pin is a Jackson no-op (a field-level
    // @JsonProperty already overrides the class strategy, and the value pinned IS what that strategy
    // produces) that makes the blind generator agree.
    RetrofitPinnedNames pinned = RetrofitPinnedNames.empty();
    pinned.record(MODEL_PACKAGE + ".common.NamedAddress", "addressLine1", "AddressLine1");
    pinned.record(MODEL_PACKAGE + ".common.NamedAddress", "postTown", "PostTown");
    // A field that already carries its OWN @JsonProperty, recorded here to prove the emitter's
    // independent refusal even if a caller asked for it (the graph never records such a field).
    pinned.record(MODEL_PACKAGE + ".common.AnnotatedNamedAddress", "county", "County");
    String diff = buildEmitter(pinned).emit().unifiedDiff();

    // Each recorded name gains the id the graph matched, and the import the class lacked is added.
    assertThat(pinAnnotations(diff))
        .containsExactly("@JsonProperty(\"AddressLine1\")", "@JsonProperty(\"PostTown\")");
    assertThat(fileHunk(diff, "common/NamedAddress.java"))
        .contains("+import com.fasterxml.jackson.annotation.JsonProperty;");
    // NOTHING beyond that: `county` on AnnotatedNamedAddress already carries @JsonProperty("CountyName"),
    // which by Jackson's precedence decided its id. A second annotation would not even compile.
    assertThat(diff).doesNotContain("AnnotatedNamedAddress.java");
    // The pinned names are the ones the graph recorded, so the patch and the emitted config agree: the
    // config references NamedAddress::getAddressLine1 and the SDK regenerates the id 'AddressLine1'.
    assertThat(fileHunk(diff, "common/NamedAddress.java"))
        .contains("+  @JsonProperty(\"AddressLine1\")")
        .contains("+  @JsonProperty(\"PostTown\")")
        .doesNotContain("County");
  }

  /**
   * The added field-level {@code @JsonProperty} pins, excluding constructor-parameter
   * annotations.
   */
  private static java.util.List<String> pinAnnotations(String diff) {
    return addedLines(diff).stream()
        .filter(l -> l.startsWith("+  @JsonProperty("))
        .map(l -> l.substring(3))
        .toList();
  }

  /** The single-file section of a multi-file unified diff, for file-scoped assertions. */
  private static String fileHunk(String diff, String pathSuffix) {
    java.util.List<String> lines = diff.lines().toList();
    StringBuilder hunk = new StringBuilder();
    boolean inFile = false;
    for (String line : lines) {
      if (line.startsWith("--- a/")) {
        inFile = line.endsWith(pathSuffix);
      }
      if (inFile) {
        hunk.append(line).append('\n');
      }
    }
    assertThat(hunk.length()).as("diff must contain a hunk for %s", pathSuffix).isPositive();
    return hunk.toString();
  }

  @Test
  void pinsNothingWhenTheMemberWalkNeededNoNamingStrategy() {
    // Demand-driven, never speculative: with an empty record — every real-world class whose members the
    // walk resolved by their own names — the patch adds no @JsonProperty at all, and no @JsonNaming
    // class in the model is touched. A speculative pass over every @JsonNaming class would rewrite
    // classes no row depends on.
    String diff = emitPatch().unifiedDiff();
    assertThat(pinAnnotations(diff)).isEmpty();
    assertThat(diff).doesNotContain("NamedAddress.java");
  }

  @Test
  void pinsAnIdThatCameFromACreatorParameterNotAClassNamingStrategy() {
    // fpl's immutable Address: the id the walk matched came from a @JsonProperty on the @JsonCreator
    // PARAMETER, and the class carries no @JsonNaming at all. The pin must carry the id the GRAPH
    // recorded — the emitter re-deriving it from a naming strategy pinned nothing here, which is not a
    // missed row but the trap itself: the walk had already committed the config to
    // CreatorNamedAddress::getAddressLine1, so an unpinned field left the SDK regenerating the CCD id
    // 'addressLine1' and silently changed it (fpl's whole 364-row category regressed this way, +203
    // residual diff lines on a lane whose passthrough had just dropped).
    RetrofitPinnedNames pinned = RetrofitPinnedNames.empty();
    pinned.record(MODEL_PACKAGE + ".common.CreatorNamedAddress", "addressLine1", "AddressLine1");
    pinned.record(MODEL_PACKAGE + ".common.CreatorNamedAddress", "postTown", "PostTown");
    String diff = buildEmitter(pinned).emit().unifiedDiff();

    assertThat(pinAnnotations(diff))
        .containsExactly("@JsonProperty(\"AddressLine1\")", "@JsonProperty(\"PostTown\")");
    assertThat(fileHunk(diff, "common/CreatorNamedAddress.java"))
        .contains("+  @JsonProperty(\"AddressLine1\")")
        .contains("+  @JsonProperty(\"PostTown\")")
        // The class already imports JsonProperty for its creator parameters; a second import would not
        // compile.
        .doesNotContain("+import com.fasterxml.jackson.annotation.JsonProperty;");
  }

  @Test
  void refusesToPinAStrategyItCannotEvaluate() {
    // probate's shape: the owning class declares a team-written @JsonNaming strategy, arbitrary Java the
    // converter cannot evaluate. Even if a name were recorded against it, no pin may be written — a
    // guessed @JsonProperty would change the CCD field id, which is strictly worse than the passthrough
    // it would be replacing. Pinning is not safe even with the id in hand: a field-level @JsonProperty
    // OVERRIDES the class strategy, so writing one onto a class whose strategy the converter cannot
    // evaluate could change the runtime payload rather than being the no-op every pin must be. (The
    // graph never records such a class in the first place; this pins the emitter's own independent
    // refusal, so neither half can start guessing alone.)
    RetrofitPinnedNames pinned = RetrofitPinnedNames.empty();
    pinned.record(MODEL_PACKAGE + ".common.CustomNamedAddress", "addressLine1", "AddressLine1");
    String diff = buildEmitter(pinned).emit().unifiedDiff();

    assertThat(pinAnnotations(diff)).isEmpty();
    assertThat(diff).doesNotContain("CustomNamedAddress.java");
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
    int nextFileHeader = lines.subList(hunkStart + 1, lines.size())
        .indexOf("--- a/uk/gov/hmcts/example/model/common/Party.java");
    java.util.List<String> hostHunkLines = nextFileHeader < 0
        ? lines.subList(hunkStart, lines.size())
        : lines.subList(hunkStart, hunkStart + 1 + nextFileHeader);
    assertThat(hostHunkLines).noneMatch(l -> l.contains("No newline at end of file"));
  }
}
