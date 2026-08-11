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

  private static final String STATE_ENUM = "uk.gov.hmcts.example.enums.State";

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
    // The same declaration bindings RetrofitConverter computes once and hands to every consumer: the
    // definition IDs no name lookup reaches, bound to the class their referencing field is declared as.
    Map<String, ModelSourceIndex.Type> declaredBindings =
        new RetrofitTypeBinder(matcher.index(), MODEL_PACKAGE)
            .bind(ir, "EXAMPLE", matcher.resolution().properties);
    rebinder.bindDeclaredFixedLists(declaredBindings.entrySet().stream()
        .filter(e -> e.getValue().isEnum())
        .map(Map.Entry::getKey)
        .collect(java.util.stream.Collectors.toSet()));
    CaseTypeModel rebound = rebinder.rebind(linked);

    RetrofitPatchEmitter emitter = new RetrofitPatchEmitter(matcher.index(), matcher.resolution(),
        rebound, matcher.root(), CONFIG_PACKAGE, 0, "", pinnedNames);
    emitter.bindDeclaredTypes(declaredBindings);
    emitter.bindDefinitionFixedLists(linked.getFixedLists());
    // The reused-State wiring RetrofitConverter installs when the team's enum resolves every definition
    // state. Bound unconditionally here (the fixture's enum conflicts on Withdrawn, which is what the
    // matcher test asserts) so the emitter's own behaviour is pinned: what the converter decides is
    // covered by its own gate, and every constant with no definition row is refused anyway.
    emitter.bindReusedStateEnum(STATE_ENUM,
        new StateEnumAnalyser(matcher.index()).stateIdToConstant(MODEL_PACKAGE));
    return emitter;
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
  void suppressesReachableModelClassesTheDefinitionNeverDeclares() {
    // The SDK's reachability walk reaches more classes than the definition declares ComplexTypes IDs
    // for, and ComplexTypeGenerator emits a full row set for each under its Java simple name. Two
    // shapes, both in this fixture and both all over sscs (15 spurious generated types):
    //   - Document: the team's own copy of a type the definition store knows natively (sscs's
    //     DocumentLink/DynamicList/CaseLink), so the definition declares no rows for it; and
    //   - DocItem: the {id, value} envelope of a collection, which CCD leaves implicit — the definition
    //     addresses the element's members on the value class, never the envelope.
    // The pin is NAME-LESS: with no definition ID there is nothing to name the type after, and an empty
    // name() is what keeps it inert outside ComplexTypeGenerator (CaseFieldGenerator's FieldType and
    // FieldTypeParameter overrides are all guarded on a non-empty name), so the referencing fields'
    // type derivation is untouched.
    RetrofitPatch patch = emitPatch();
    assertThat(complexTypeAnnotations(patchedFile(patch, "common/Document.java")))
        .containsExactly("@ComplexType(generate = false)");
    assertThat(complexTypeAnnotations(patchedFile(patch, "common/DocItem.java")))
        .containsExactly("@ComplexType(generate = false)");
    assertThat(patchedFile(patch, "common/Document.java"))
        .contains("import uk.gov.hmcts.ccd.sdk.api.ComplexType;");

    // A class the definition DOES declare keeps its rows: suppression is only ever for a type nothing
    // in the definition accounts for, so Party (declared verbatim) and NoticeDetails (declared
    // camelCase, pinned by name) are both left generating.
    assertThat(complexTypeAnnotations(patchedFile(patch, "common/Party.java"))).isEmpty();
    assertThat(complexTypeAnnotations(patchedFile(patch, "common/NoticeDetails.java")))
        .containsExactly("@ComplexType(name = \"noticeDetails\", generate = true)");
    // …including one bound by DECLARATION rather than name, whose ID pin the suppression pass must not
    // pre-empt: executorApplying shares nothing with AdditionalExecutorApplying, so a name-based check
    // alone would suppress the very class step 3 just named.
    assertThat(complexTypeAnnotations(
        patchedFile(patch, "common/AdditionalExecutorApplying.java")))
        .containsExactly("@ComplexType(name = \"executorApplying\", generate = true)");
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
  void pinsAnIdNoNameLookupReachesOntoTheClassItsReferencingFieldIsDeclaredAs() {
    // The single largest category of retrofit residual (4,400 of 7,119 diff lines across the
    // probate/ET/fpl/prl lanes). complexTypeClass matches an ID to a class by simple name — exactly, then
    // case-insensitively — which reaches noticeDetails → NoticeDetails but nothing further. Real
    // definitions name their types independently of the classes behind them (probate's executorApplying is
    // modelled by AdditionalExecutorApplying), and the miss costs twice over: a companion is generated for
    // the ID that nothing references (so the SDK never reflects it and it emits no rows at all) while the
    // real class emits a full set of rows under its Java name, an ID the definition never mentions.
    //
    // The definition itself says what backs the type: the field referencing it is DECLARED as the class.
    // So the ID is pinned onto that class with the same @ComplexType(name, generate = true) the
    // name-bound IDs get — which fixes ComplexTypes, FixedLists AND the CaseField FieldType/
    // FieldTypeParameter columns at once, since CaseFieldGenerator reads the same annotation.
    RetrofitPatch patch = emitPatch();
    assertThat(patchedFile(patch, "common/AdditionalExecutorApplying.java"))
        .contains("@ComplexType(name = \"executorApplying\", generate = true)")
        .contains("import uk.gov.hmcts.ccd.sdk.api.ComplexType;");
    // No field declaration is rewritten, so unlike the retype this cannot break a caller in a published
    // jar — and its member is annotated in place on the class the definition's rows describe.
    assertThat(patchedFile(patch, "common/Party.java"))
        .contains("private AdditionalExecutorApplying executorApplying;");
  }

  @Test
  void pinsAFixedListIdNoNameLookupReachesOntoTheEnumItsReferencingFieldIsDeclaredAs() {
    // The FixedLists half, on the identical mechanism: FixedListGenerator reads the list ID from the same
    // class-level @ComplexType(name), falling back to the enum's simple name. probate's
    // handoffReasonFixedList is declared as HandoffReasonId, so without the pin the definition's list is
    // answered by a companion enum the retype usually cannot point the field at (196 refusals across the
    // lanes, 141 of them "has accessors called from the model source") while the team's enum emits under
    // its own name.
    RetrofitPatch patch = emitPatch();
    assertThat(patchedFile(patch, "enums/HandoffReasonId.java"))
        .contains("@ComplexType(name = \"handoffReasonFixedList\", generate = true)");
  }

  @Test
  void namesTheCompanionBehindAFixedListNoFieldDeclares() {
    // sscs's hearingEpimsId shape, and the largest single cause of residual across the lanes (~1,095
    // lines on sscs alone): 160-odd venue codes are loaded at runtime, so the team really models the
    // field as a String. A typeParameterOverride only writes the FieldTypeParameter column, while the
    // FixedLists ROWS come from the types reflection reaches — so the field named a list nothing
    // generated. @CCD(typeParameterClass) makes the generated companion reachable without retyping the
    // field, which would change every caller and serialised payload in a published jar.
    RetrofitPatch patch = emitPatch();
    String patched = patchedFile(patch, "model/CaseData.java");
    assertThat(patched)
        .contains("typeParameterOverride = \"FL_hearingVenues\"")
        .contains("typeParameterClass = HearingVenues.class")
        // The declaration is untouched — that is the point of naming the class rather than retyping.
        .contains("private String hearingVenue;");
  }

  @Test
  void namesTheCompanionOnAComplexTypeMemberAndImportsItAcrossPackages() {
    // The same reach gap on a nested member rather than a root field, so the two annotate sites cannot
    // drift apart. Party.crossKind is declared as the CLASS CrossKindPayload while the definition says
    // FixedList(crossKindFixedList) — the cross-kind binding is refused, so a companion enum IS
    // generated for that ID and naming it is what makes its rows appear. The companion lands in the
    // model package, which is Party's own package's parent, so the patch must add the import too.
    String patched = patchedFile(emitPatch(), "common/Party.java");
    assertThat(patched)
        .contains("typeParameterClass = CrossKindFixedList.class")
        .contains("import " + MODEL_PACKAGE + ".CrossKindFixedList;");
  }

  @Test
  void namesTheTeamsOwnEnumForAListNoFieldDeclares() {
    // The other half of the same reach gap, and the one a companion cannot close: the team DOES model the
    // list as an enum, but every column typed by it is a String carrying only the ID as a
    // typeParameterOverride (sscs's ScannedDocumentDetails.type, with the real 14-constant enum sitting
    // unreferenced in …ccd.callback). Reflection reaches an enum only from a field's DECLARED type, so
    // nothing generated the list's rows — and no companion is generated either, since an enum of that
    // name already exists. Naming the team's own enum is the only thing that makes its rows appear, and
    // the import must be for ITS package rather than the companions' model package.
    String patched = patchedFile(emitPatch(), "common/Party.java");
    assertThat(patched)
        .contains("typeParameterClass = ScannedDocumentType.class")
        .contains("import uk.gov.hmcts.example.callback.ScannedDocumentType;")
        // Named, not retyped: the declaration is what a published jar's callers bind to.
        .contains("private String scannedDocumentType;");
  }

  @Test
  void pinsTheDefinitionsCodeOntoAnEnumSpellingItInItsOwnHouseStyle() {
    // FixedListGenerator does not read the ListElementCode off any SDK annotation — it puts the enum
    // CONSTANT into the row map and lets Jackson serialise it. That serialisation IS the seam: a
    // @JsonProperty on the constant redirects the emitted code. So an enum whose codes the team spells in
    // its own house style (sscs's ScannedDocumentType carries the definition's `cherished` as a
    // constructor field while the constant is CHERISHED) can supply the list's rows after all — each
    // constant is pinned to its definition code, and the enum is then named like any other.
    //
    // Unlike the label pin this is NOT runtime-neutral: it changes how the team's own enum serialises
    // everywhere, not just what the generator emits. The value comes from the definition, which is what
    // that column already carries on the wire, so the redirect aligns the type with its own data.
    String houseStyle = patchedContent(emitPatch(), "callback/HouseStyleType.java");
    // Both pins land on each constant, code first: the label pin follows the CODE, not the constant
    // name — once FIRST_STYLE emits `firstStyle`, the generator's own fallback emits `firstStyle` as the
    // ListElement too, so "First style" still needs pinning, and would NOT have been pinned by a
    // comparison against the constant name.
    assertThat(houseStyle)
        .contains("  @JsonProperty(\"firstStyle\")\n  @CCD(label = \"First style\")\n"
            + "  FIRST_STYLE(\"firstStyle\"),")
        .contains("  @JsonProperty(\"secondStyle\")\n  @CCD(label = \"Second style\")\n"
            + "  SECOND_STYLE(\"secondStyle\");")
        .contains("import com.fasterxml.jackson.annotation.JsonProperty;");
    // With its codes reachable, the enum is named by the field that only carried its ID.
    assertThat(patchedFile(emitPatch(), "common/Party.java"))
        .contains("typeParameterClass = HouseStyleType.class")
        .contains("import uk.gov.hmcts.example.callback.HouseStyleType;")
        // Named, not retyped: the declaration is what a published jar's callers bind to.
        .contains("private String houseStyleType;");
  }

  @Test
  void pinsALabelWhoseConstantsExistingJsonPropertyItsJsonValueOverrides() {
    // Regression (prl DocumentPartyEnum, one residual line): deciding whether a label pin is still needed
    // means knowing the code the constant REALLY emits, and an existing @JsonProperty only tells you that
    // when the enum honours it. ShadowedPinList pins @JsonProperty("Court") onto COURT and serialises
    // through a @JsonValue getDisplayedValue(), so the emitted code is COURT, not Court. Reading the
    // annotation blindly concluded the constant already emitted `Court`, matched the definition's
    // ListElement, and dropped the very pin that column needed.
    assertThat(patchedContent(emitPatch(), "enums/ShadowedPinList.java"))
        .contains("@CCD(label = \"Court\")");
  }

  @Test
  void refusesToNameAnEnumThatRedirectsItsSerialisedCode() {
    // The refusal a code pin cannot lift: a @JsonValue takes precedence over a constant's
    // @JsonProperty, so what the enum emits is a method's return value and nothing pinned on the
    // constants can change it — sscs's DocumentTabChoice really emits document/internalDocument for
    // REGULAR/INTERNAL. This fixture's constants ARE the definition's codes (FIRST/SECOND), so only
    // reading the @JsonValue catches that the list would still be wrong.
    String patched = patchedFile(emitPatch(), "common/Party.java");
    assertThat(patched)
        .doesNotContain("JsonValuedType.class")
        .contains("private String jsonValuedType;");
    // And no code is pinned onto its constants either: a @JsonProperty the @JsonValue overrides is a
    // change to the team's published serialisation that buys nothing, and would misrepresent the list as
    // fixed. (The LABEL pins are unaffected and still emitted — they are read off the definition and are
    // correct for this enum however its codes serialise.)
    assertThat(patchedContent(emitPatch(), "callback/JsonValuedType.java"))
        .doesNotContain("@JsonProperty");
  }

  @Test
  void addsTheConstantAnEnumIsMissingForOneOfTheDefinitionsCodes() {
    // Every code must be accounted for, not most — but a code with no constant is not a mystery: the
    // definition holds its code and label, and the SDK derives the whole list from the constant set, so
    // the faithful reproduction of a three-row list by a two-constant enum IS a third constant.
    // PartialCodeType has firstKind and secondKind but none for thirdKind; the constant is added, and the
    // list then round-trips in full instead of being refused for the one row.
    String partial = patchedContent(emitPatch(), "callback/PartialCodeType.java");
    // Declared after the last existing constant, which gives up the `;` terminator to it. Its
    // constructor call copies its siblings' SHAPE — one string literal — and the value is the
    // definition's own code, because every existing constant provably passes its own code there.
    assertThat(partial)
        .contains("  SECOND_KIND(\"secondKind\"),\n"
            + "  @JsonProperty(\"thirdKind\")\n"
            + "  @CCD(label = \"Third kind\")\n"
            + "  THIRD_KIND(\"thirdKind\");")
        .contains("import com.fasterxml.jackson.annotation.JsonProperty;");
    // The two constants that already matched are pinned as they would be on any house-style enum.
    assertThat(partial)
        .contains("  @JsonProperty(\"firstKind\")\n  @CCD(label = \"First kind\")\n"
            + "  FIRST_KIND(\"firstKind\"),");
    // With every code now reachable, the enum is named like any other.
    assertThat(patchedFile(emitPatch(), "common/Party.java"))
        .contains("typeParameterClass = PartialCodeType.class")
        .contains("private String partialCodeType;");
  }

  @Test
  void addsNoConstantForACodeAnExistingConstantAlreadyPins() {
    // Regression (sscs CommunicationRequestTopic, five duplicated rows): a constant carries a code when it
    // EMITS it, which it does by its name OR by a @JsonProperty the enum honours. PinnedCodeType names
    // ALPHA_TOPIC_LONG_NAME and pins `alphaTopic`, so resolving by name alone concluded the code had no
    // constant and added a second one emitting it — two rows for one code, worse than the label
    // divergence the addition was closing.
    String pinned = patchedContent(emitPatch(), "callback/PinnedCodeType.java");
    assertThat(pinned)
        .doesNotContain("ALPHA_TOPIC(")
        .doesNotContain("BETA_TOPIC(")
        // Nor is a second @JsonProperty added to a constant that already carries the right one: the
        // annotation is not @Repeatable, so that would not even compile.
        .doesNotContain("@JsonProperty(\"alphaTopic\")\n  @JsonProperty");
    // Its labels ARE pinned — the constant emits `alphaTopic`, so the generator's fallback would put that
    // in the ListElement column where the definition says "Alpha topic".
    assertThat(pinned).contains("@CCD(label = \"Alpha topic\")");
    // And with every code accounted for, the enum is named.
    assertThat(patchedFile(emitPatch(), "common/Party.java"))
        .contains("typeParameterClass = PinnedCodeType.class");
  }

  @Test
  void refusesToAddAConstantWhoseConstructorArgumentsCannotBeEstablished() {
    // The refusal synthesis does NOT lift. UnsynthesisableType is missing thirdSort, but its constants
    // pass a Category reference alongside the code: what a new constant should pass there is a guess —
    // the definition says nothing about it and no unanimous rule claims the position. A guess that fails
    // to compile breaks the team's build, and one that compiles puts a wrong value in their model, so the
    // enum stays refused and the constant-set divergence is reported instead.
    String patched = patchedFile(emitPatch(), "common/Party.java");
    assertThat(patched)
        .doesNotContain("UnsynthesisableType.class")
        .contains("private String unsynthesisableType;");
    // And nothing is pinned onto the constants that DO match: pinning them is what would emit the
    // two-right-one-missing list. The refusal is all-or-nothing, per enum, not per constant.
    assertThat(patchedContent(emitPatch(), "callback/UnsynthesisableType.java"))
        .doesNotContain("@JsonProperty")
        .doesNotContain("THIRD_SORT");
  }

  @Test
  void addsNoConstantForACodeTheEnumAlreadySaysAnotherWay() {
    // Adding a constant closes a GAP — a value the CCD column carries that the enum cannot name. Here it
    // can: RestatedType has no constant named HOUR_1, but SIXTY_MINUTES already passes that row's label,
    // so adding one would put two ways to say "1 hour" into a published enum AND still leave
    // SIXTY_MINUTES emitting an extra row. Found on civil, whose HearingLengthFinalOrderList declares
    // nineteen constants for a six-code list.
    // Over ADDED lines only: the fixture's own javadoc names HOUR_1 to explain itself, so the whole-file
    // text cannot distinguish a declared constant from a documented one.
    assertThat(addedLines(filePatch(emitPatch(), "callback/RestatedType.java")))
        .noneMatch(l -> l.contains("HOUR_1"))
        .noneMatch(l -> l.contains("@JsonProperty"));
    assertThat(patchedFile(emitPatch(), "common/Party.java"))
        .doesNotContain("RestatedType.class")
        .contains("private String restatedType;");
  }

  @Test
  void namesNoCompanionForAListAModelEnumAlreadyServes() {
    // The guard: a list whose rows come from a model enum is ALREADY reachable as a declared field type,
    // so naming a class here would either name one that was never generated (the rebinder drops the
    // companion whenever an enum serves the ID) or disagree with the FixedLists pin about which list
    // that enum serves. ClaimType is reached by its own field's declaration; handoffReasonFixedList is
    // bound by declaration to HandoffReasonId, which the patch pins the ID onto instead.
    String diff = emitPatch().unifiedDiff();
    assertThat(addedLines(diff))
        .noneMatch(l -> l.contains("typeParameterClass = ClaimType.class"))
        .noneMatch(l -> l.contains("typeParameterClass = HandoffReasonId.class"))
        .noneMatch(l -> l.contains("typeParameterClass = HandoffReasonFixedList.class"));
  }

  @Test
  void refusesToBindAnIdTwoReferencingFieldsDeclareDifferently() {
    // Unanimity: disagreeingCT is referenced by one field declared as DeclaredOne and another as
    // DeclaredTwo. There is no single backing class, so binding to whichever row was read first would pin
    // an ID onto a class only half the definition's references agree with. Left to the companion path.
    RetrofitPatch patch = emitPatch();
    assertNoPinOn(patch, "common/DeclaredOne.java", "disagreeingCT");
    assertNoPinOn(patch, "common/DeclaredTwo.java", "disagreeingCT");
  }

  /**
   * Asserts a class carries no {@code @ComplexType} pin for a definition ID — whether because the patch
   * leaves the file untouched entirely (the usual outcome for a refused binding: nothing else about the
   * class needs changing) or because it patches it for other reasons but adds no pin.
   */
  private static void assertNoPinOn(RetrofitPatch patch, String pathSuffix, String definitionId) {
    java.util.Optional<String> patched = patch.files().stream()
        .filter(f -> f.relativePath().endsWith(pathSuffix))
        .map(RetrofitPatch.FilePatch::patchedContent)
        .findFirst();
    patched.ifPresent(content -> assertThat(complexTypeAnnotations(content))
        .noneMatch(a -> a.contains("\"" + definitionId + "\"")));
  }

  @Test
  void pinsTheDefinitionsListElementOntoEachEnumConstant() {
    // FixedListGenerator resolves a constant's ListElement through HasLabel → @CCD(label) → @CCD(hint) →
    // the constant name. Teams carry labels outside that contract (prl's getDisplayedValue() behind a
    // @JsonValue, fpl's getLabel(Language)), so the generator falls through and emits
    // ListElement == ListElementCode. The label is copied from the DEFINITION rather than read off
    // whichever accessor the team happens to have: the definition's own ListElement is by construction
    // the string the round-trip must reproduce, so no guess about which member is the CCD label can be
    // wrong. ClaimType is reached by name (its simple name IS the list ID)...
    String claimType = patchedContent(emitPatch(), "enums/ClaimType.java");
    assertThat(claimType).contains("@CCD(label = \"Personal injury\")");
    // ...and every character that would break the literal is escaped, exactly as the field renderer
    // does it — the labels come from the same definition and carry the same punctuation.
    assertThat(claimType).contains("@CCD(label = \"A \\\"contract\\\" dispute,\\nover multiple lines\")");
    // A constant whose definition label already equals its name needs no pin: the generator's own
    // fallback emits the right value, so annotating it would be pure noise in the team's diff.
    assertThat(claimType).doesNotContain("@CCD(label = \"DEBT\")");
    // ...while HandoffReasonId is reached only by the declaration binding. Both emit their list's rows,
    // so both need the labels.
    assertThat(patchedContent(emitPatch(), "enums/HandoffReasonId.java"))
        .contains("@CCD(label = \"Interpreter required\")")
        .contains("@CCD(label = \"Trust corporation\")");
    // A row is matched on the raw ListElementCode as well as on the sanitised constant name, because
    // teams spell the constant either way — prl writes the code verbatim, civil upper-snakes it. An
    // annotation the constant already carries is kept: the pin goes above the constant's begin line,
    // which IS that annotation's line, so the two stack in source order.
    assertThat(patchedContent(emitPatch(), "enums/CamelConstantList.java"))
        .contains("  @CCD(label = \"Non-molestation order (FL404A)\")\n"
            + "  @JsonProperty(\"nonMolestationOrder\")\n"
            + "  nonMolestationOrder,")
        .contains("@CCD(label = \"Occupation order (FL404)\")");
  }

  @Test
  void pinsTheDefinitionsStateNameTitleDisplayAndDescriptionOntoTheReusedStateEnum() {
    // StateGenerator reads all three State-sheet columns off @CCD on the constant — label() → Name
    // (falling back to the state ID), description() → Description (falling back to the resolved Name),
    // hint() → TitleDisplay (the column is simply absent without one). A team's own State enum carries
    // none of them, so every reused state emitted Name == Description == the state ID and no
    // TitleDisplay at all. As with the FixedLists labels the values are copied from the definition, so
    // no SDK change is needed and no guess about which accessor holds the display name can be wrong.
    String state = patchedContent(emitPatch(), "enums/State.java");

    // Only the columns the generator would not already produce unaided are pinned. OPEN's state ID IS
    // its Name ("Open"), which is exactly what label() falls back to, so only its Description is
    // written — the pin never repeats a value the fallback already gets right.
    assertThat(state).contains("  @CCD(description = \"The case is open\")\n"
        + "  @JsonProperty(\"Open\")\n"
        + "  OPEN,");

    // Members in EnumEmitter's own order (label, hint, description) so a retrofitted constant reads
    // exactly like a generated one; wrapped one per line by the same rule a long field annotation uses,
    // since a real TitleDisplay is markup that blows past the house line limit on its own. The pin lands
    // above the constant's begin line, which IS its existing @JsonProperty's line, so the two stack in
    // source order.
    assertThat(state).contains("  @CCD(\n"
        + "          label = \"Case management\",\n"
        + "          hint = \"# #${[CASE_REFERENCE]} <br/> ${applicantName}: awaiting case management "
        + "directions\"\n"
        + "  )\n"
        + "  @JsonProperty(\"PREPARE_FOR_HEARING\")\n"
        + "  CASE_MANAGEMENT,");
    // ...and NOT a description, because "Case management" is what StateGenerator already defaults the
    // Description to once that label is pinned. Pinning it would be noise in the team's diff.
    assertThat(state).doesNotContain("description = \"Case management\"");

    // All three at once, escaped through the same quoting the field renderer uses.
    assertThat(state).contains("@CCD(label = \"Closed\", hint = \"# ${[CASE_REFERENCE]}\", "
        + "description = \"The \\\"final\\\" state\")");

    // A constant the team already annotated is left alone — @CCD is not @Repeatable, so a second one
    // would not compile — and a constant with no definition row (STAYED) is a constant-set divergence,
    // reported elsewhere, not something to guess a label for. Withdrawn is the mirror case: a definition
    // state with no constant, which the reuse decision itself gates on.
    assertThat(state).contains("  @CCD(label = \"Stayed by the team\")\n  STAYED,");
    assertThat(state).doesNotContain("Withdrawn");
    // OPEN, CASE_MANAGEMENT, the team's own STAYED, LEGACY_COMPOSITE's ignore pin, CLOSED — and nothing
    // else. No label is guessed for a constant with no definition row: LEGACY_COMPOSITE's annotation is
    // the ignore pin alone (see pinsIgnoreOnAStateConstantTheDefinitionHasNoRowFor).
    assertThat(state.split("@CCD\\(", -1)).hasSize(6);
  }

  @Test
  void pinsIgnoreOnAStateConstantTheDefinitionHasNoRowFor() {
    // StateGenerator emits one State row per constant with no filter, so a constant no case type declares
    // emits a state the definition never had — sscs's @JsonEnumDefaultValue `unknown` and its legacy
    // composite `withdrawnRevisedStruckOutLapsedState`, civil's 18. The team's own code switches on them
    // so they cannot be deleted; @CCD(ignore = true) is how the constant declares it contributes nothing.
    String state = patchedContent(emitPatch(), "enums/State.java");

    assertThat(state).contains("  @CCD(ignore = true)\n  LEGACY_COMPOSITE,");
    // Refused where a @CCD already exists — @CCD is not @Repeatable — even though STAYED is the same
    // divergence. The team's annotation stands and the residual row is the honest outcome.
    assertThat(state).contains("  @CCD(label = \"Stayed by the team\")\n  STAYED,");
    // And a constant the definition DOES declare is never ignored, whatever else it is pinned.
    assertThat(state).doesNotContain("ignore = true, label")
        .doesNotContain("label = \"Case management\", ignore");
  }

  @Test
  void theStateLabelsWinTheConstantWhenTheStateEnumAlsoBacksAFixedList() {
    // A State enum is frequently ALSO reachable as a declared field type (sscs declares
    // `private State state`), so reflection emits a FixedLists/State and BOTH pins want the same
    // constant. Only one @CCD per constant compiles, so they share one per-constant claim and the State
    // sheet takes it: those three columns are always compared, whereas a fixed list the definition does
    // not reference is an unexpected row whichever ListElement it carries.
    String state = patchedContent(emitPatch(), "enums/State.java");

    assertThat(state).contains("@CCD(description = \"The case is open\")");
    assertThat(state).doesNotContain("An open case");
    // CLOSED's State row gives it all three columns, so the list's own label loses there too.
    assertThat(state).doesNotContain("A closed case");

    // And the fixed-list pass really does reach this enum — so the assertions above are a precedence
    // result, not a pass that never ran. With no State reused, its labels are exactly what lands.
    RetrofitPatchEmitter listOnly = buildEmitter();
    listOnly.bindReusedStateEnum(null, Map.of());
    assertThat(patchedContent(listOnly.emit(), "enums/State.java"))
        .contains("@CCD(label = \"An open case\")")
        .contains("@CCD(label = \"A closed case\")");
  }

  @Test
  void pinsNoStateLabelsWhenTheRunGeneratesItsOwnState() {
    // The pins apply only to an enum this run REUSES as the case type's State. When the converter
    // generates a fresh State enum instead (any definition state the team's enum cannot express), that
    // enum carries these same three columns itself and the team's is just another model type — which
    // must gain none of the State sheet's values, whatever else the other passes do to it.
    RetrofitPatchEmitter emitter = buildEmitter();
    emitter.bindReusedStateEnum(null, Map.of());

    String state = patchedContent(emitter.emit(), "enums/State.java");
    assertThat(state)
        .doesNotContain("The case is open")
        .doesNotContain("Case management")
        .doesNotContain("${[CASE_REFERENCE]}")
        .doesNotContain("final\\\" state");
  }

  @Test
  void splitsAConstantLineSharedBySeveralConstantsBeforePinningLabels() {
    // @CCD is not @Repeatable, so `FIRST, SECOND, THIRD;` on one line cannot take two annotations
    // above it — the line is rewritten to one constant per line first. Each constant's text is the
    // verbatim column slice of the original, and the trailing `;` closing the constant list rides on
    // the last emitted line, so nothing but the line breaks and the pins changes.
    assertThat(patchedContent(emitPatch(), "enums/SharedLineList.java"))
        .contains("  @CCD(label = \"The very first\")\n"
            + "  FIRST,\n"
            + "  SECOND,\n"
            + "  @CCD(label = \"The third\")\n"
            + "  THIRD;\n");
  }

  @Test
  void refusesToPinLabelsOnEnumsTheGeneratorAlreadyReadsALabelFrom() {
    // HasLabel wins in FixedListGenerator's own resolution order, so a @CCD(label) here would be read
    // by nothing — pinning it would dirty the team's source while claiming a fix it did not make.
    // Asserted as "the patch does not pin here", which a refusal satisfies either by leaving the file out
    // of the patch entirely (the usual outcome — nothing else about these enums needs changing) or by
    // patching it for some other reason while adding no label.
    RetrofitPatch patch = emitPatch();
    contentIfPatched(patch, "enums/LabelBearingList.java")
        .ifPresent(content -> assertThat(content).doesNotContain("@CCD(label"));
    // And a constant already carrying a team-written @CCD keeps exactly that one: a second annotation
    // would not compile, and overwriting the team's label would be the patch making a product decision.
    contentIfPatched(patch, "enums/AnnotatedList.java").ifPresent(content -> {
      assertThat(content).contains("@CCD(label = \"Team's own label\")");
      assertThat(content).doesNotContain("Definition label");
    });
  }

  /**
   * The patched content of the file whose path ends with {@code pathSuffix}, or empty when the patch
   * does not touch it — unlike {@link #patchedContent}, which requires the file to be patched.
   */
  private static java.util.Optional<String> contentIfPatched(
      RetrofitPatch patch, String pathSuffix) {
    return patch.files().stream()
        .filter(f -> f.relativePath().endsWith(pathSuffix))
        .map(RetrofitPatch.FilePatch::patchedContent)
        .findFirst();
  }

  @Test
  void refusesToBindTwoIdsToOneDeclaredClass() {
    // The declaration-bound counterpart of the name-bound one-class-many-IDs collision: firstClaimingCT
    // and secondClaimingCT are both declared as TwiceClaimedPayload, which carries only one
    // @ComplexType(name). Neither is bound — picking a winner here would be arbitrary, and would silently
    // move the loser's rows onto the winner's ID.
    RetrofitPatch patch = emitPatch();
    assertNoPinOn(patch, "common/TwiceClaimedPayload.java", "firstClaimingCT");
    assertNoPinOn(patch, "common/TwiceClaimedPayload.java", "secondClaimingCT");
  }

  @Test
  void refusesToPinAListIdOntoAnEnumDeclaringMoreConstantsThanTheListHasCodes() {
    // FixedListGenerator emits one row per enum constant and takes no filter, so the ID pin decides
    // which enum IS the list — wholesale. TeamOwnVocabulary carries the definition's two codes plus two of
    // the team's own (sscs's EventType: 261 constants against a 15-code list, 255 diff lines), so
    // pinning FL_oversizedList onto it would turn a list with no rows into a list with WRONG rows.
    // Unlike @CCD(typeParameterClass), which makes an unreachable list reachable and where a superset is
    // strictly better than nothing, this is the definitive answer being wrong.
    RetrofitPatch patch = emitPatch();
    assertNoPinOn(patch, "enums/TeamOwnVocabulary.java", "FL_oversizedList");
    // Refusing loses nothing: the ID stays unbound, so RetrofitModelRebinder keeps its companion, and
    // the field that DECLARES the team's enum is pointed at that companion — both halves, because a
    // FixedRadioList field needs no typeParameterOverride to round-trip normally (the SDK derives the
    // column from the declared enum), so without the override there is nothing to attach a class to and
    // the companion would be emitted with nothing referencing it while the team's enum emitted four rows
    // under its own Java name. sscs's HmcHearingType regressed exactly that way.
    assertThat(patchedContent(patch, "model/CaseData.java"))
        .contains("typeParameterOverride = \"FL_oversizedList\"")
        .contains("typeParameterClass = OversizedList.class");
    // And the field keeps its declared type: nothing about how the team's code reads or serialises this
    // property changes, only which enum the generator reads the list's rows from.
    assertThat(patchedContent(patch, "model/CaseData.java"))
        .contains("private TeamOwnVocabulary oversized;");
  }

  @Test
  void refusesToBindAFixedListIdToAClassOrAComplexTypeIdToAnEnum() {
    // Kind must match the generator that will emit the type: FixedListGenerator selects on isEnum and
    // ComplexTypeGenerator on the absence of it, so pinning crossKindFixedList onto the CLASS
    // CrossKindPayload would name a type the OTHER generator emits — the list would still get no rows
    // while the class's own moved to an ID the definition uses for something else.
    RetrofitPatch patch = emitPatch();
    assertNoPinOn(patch, "common/CrossKindPayload.java", "crossKindFixedList");
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
  void namesTheCompanionOnAFieldWhoseAccessorsTheModelCallsRatherThanRetypingIt() {
    // SummaryReader assigns party.getReadSummary() to a SharedSummary. Re-declaring the field changes
    // what that getter returns, so the call stops compiling — the patch must leave the declaration alone.
    // Matched by method NAME alone (the index has no symbol solver), which is deliberately conservative.
    // The refusal is then COVERED rather than reported: @CCD(typeParameterClass) names the companion, so
    // the SDK reads its @ComplexType(name) as this column's FieldType and the companion emits the
    // definition's rows, while the declared type — and every caller — is untouched.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    assertThat(patchedFile(patch, "common/Party.java"))
        .contains("private SharedSummary readSummary;")
        .doesNotContain("ReadSummaryCT readSummary;")
        .contains("typeParameterClass = ReadSummaryCT.class");
    assertNoGapFor(emitter, "Party/readSummary");
  }

  @Test
  void namesTheCompanionOnAFieldTheDeclaringClassReadsDirectlyRatherThanRetypingIt() {
    // Party.resolveInlineSummary() returns inlineReadSummary as a SharedSummary with no accessor in
    // between (fpl's CaseData.getOrders() shape, which returned the retyped ordersSolicitor as an
    // Orders). The accessor check cannot see this — there is no get/set call — so the refusal must also
    // look for the field read as a bare identifier inside its own declaring source. Covered by naming the
    // companion, which needs no declaration change and so has none of the retype's refusals.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    assertThat(patchedFile(patch, "common/Party.java"))
        .contains("private SharedSummary inlineReadSummary;")
        .doesNotContain("InlineReadSummaryCT inlineReadSummary;")
        .contains("typeParameterClass = InlineReadSummaryCT.class");
    assertNoGapFor(emitter, "Party/inlineReadSummary");
  }

  @Test
  void namesTheCompanionOnAFieldSetThroughABuilderMethodNamedAfterItRatherThanRetypingIt() {
    // SummaryReader.build() calls Party.builder().builderSetSummary(summary) — a Lombok @Builder setter
    // named after the FIELD, with no get/set prefix for the accessor check to match (fpl's
    // .respondents(respondentsInCase) shape). The retype changes that parameter's type, so it is refused
    // and covered by naming the companion instead.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    assertThat(patchedFile(patch, "common/Party.java"))
        .contains("private SharedSummary builderSetSummary;")
        .doesNotContain("BuilderSetSummaryCT builderSetSummary;")
        .contains("typeParameterClass = BuilderSetSummaryCT.class");
    assertNoGapFor(emitter, "Party/builderSetSummary");
  }

  @Test
  void namesTheCompanionOnAFieldShadowedElsewhereInItsHierarchyRatherThanRetypingIt() {
    // ShadowBase/ShadowChild declare the same two field names, so Lombok generates an overriding
    // accessor pair per declaration (ET's BaseCaseData/CaseData shape). Retyping either declaration
    // alone breaks the override, so both directions must refuse: the definition addresses
    // baseAddressedSummary on the SUPERCLASS (shadowed by a descendant) and childAddressedSummary on
    // the SUBCLASS (shadowed by an ancestor). Each refusal is covered on the declaration the definition
    // actually addresses, by naming its companion — which leaves both declarations, and so the override
    // pair, exactly as they were.
    RetrofitPatchEmitter emitter = buildEmitter();
    RetrofitPatch patch = emitter.emit();
    assertThat(patchedFile(patch, "common/ShadowBase.java"))
        .contains("private SharedSummary baseAddressedSummary;")
        .doesNotContain("BaseShadowSummaryCT baseAddressedSummary;")
        .contains("typeParameterClass = BaseShadowSummaryCT.class");
    assertThat(patchedFile(patch, "common/ShadowChild.java"))
        .contains("private SharedSummary childAddressedSummary;")
        .doesNotContain("ChildShadowSummaryCT childAddressedSummary;")
        .contains("typeParameterClass = ChildShadowSummaryCT.class");
    assertNoGapFor(emitter, "ShadowBase/baseAddressedSummary");
    assertNoGapFor(emitter, "ShadowChild/childAddressedSummary");
  }

  /**
   * Asserts no gap was recorded for a row key — the retype refusal was COVERED by naming the companion
   * on the field's {@code @CCD}, so there is nothing left for a human to place by hand.
   */
  private static void assertNoGapFor(RetrofitPatchEmitter emitter, String rowKey) {
    assertThat(emitter.gaps()).noneSatisfy(g -> assertThat(g.getRowKey()).isEqualTo(rowKey));
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
   * The added field-level {@code @JsonProperty} pins, excluding constructor-parameter annotations and
   * the ENUM-CONSTANT pins that carry a fixed list's {@code ListElementCode}. The two are unrelated
   * concerns that happen to share an annotation: this one makes a naming strategy's already-effective id
   * explicit (a Jackson no-op), the other deliberately redirects a constant's serialised code. The
   * fixture keeps every fixed-list enum in {@code …example.callback}, so scoping by that package
   * separates them without the diff having to say which types are enums.
   */
  private static java.util.List<String> pinAnnotations(String diff) {
    java.util.List<String> pins = new java.util.ArrayList<>();
    boolean inFieldFile = false;
    for (String line : diff.lines().toList()) {
      if (line.startsWith("--- a/")) {
        inFieldFile = !line.contains("/callback/");
      }
      if (inFieldFile && line.startsWith("+  @JsonProperty(")) {
        pins.add(line.substring(3));
      }
    }
    return pins;
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
