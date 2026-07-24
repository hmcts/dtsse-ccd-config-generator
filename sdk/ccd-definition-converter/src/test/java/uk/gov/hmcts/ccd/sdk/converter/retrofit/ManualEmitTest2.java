package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.link.DefaultDefinitionLinker;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.reader.JsonDefinitionReader;

public class ManualEmitTest2 {
  public static void main(String[] args) throws Exception {
    Path MODEL_ROOT = Path.of("/tmp/civilrepro4/model/src").toAbsolutePath();
    Path DEFINITION = Path.of("/tmp/civilrepro4/definition").toAbsolutePath();
    String MODEL_PACKAGE = "uk.gov.hmcts.reform.civil.model";
    String CONFIG_PACKAGE = "uk.gov.hmcts.reform.civil.model.ccd.generated";
    Map<String, OverlayCondition> overlays = new LinkedHashMap<>();
    overlays.put("prod", OverlayCondition.parse("CCD_DEF_ENV:prod"));
    overlays.put("nonprod", OverlayCondition.parse("!CCD_DEF_ENV:prod"));
    ConversionOptions options = ConversionOptions.builder()
        .inputs(java.util.List.of(DEFINITION))
        .caseTypeId("REPRO")
        .modelPackage(MODEL_PACKAGE)
        .configPackage(CONFIG_PACKAGE)
        .overlaySuffixes(overlays)
        .retrofit(true)
        .retrofitCaseDataClass("CorrectEmail")
        .build();
    DefinitionIr ir = new JsonDefinitionReader().read(options, new GapCollector());
    RetrofitMatcher matcher = new RetrofitMatcher(ir, "REPRO", MODEL_ROOT, MODEL_PACKAGE, "CorrectEmail");
    matcher.match();
    CaseTypeModel linked = new DefaultDefinitionLinker().link(ir, options, new GapCollector());
    RetrofitModelRebinder rebinder = new RetrofitModelRebinder(matcher.index(), matcher.resolution());
    CaseTypeModel rebound = rebinder.rebind(linked);
    RetrofitPatchEmitter emitter = new RetrofitPatchEmitter(
        matcher.index(), matcher.resolution(), rebound, matcher.root(), CONFIG_PACKAGE);
    RetrofitPatch patch = emitter.emit();
    for (RetrofitPatch.FilePatch fp : patch.files()) {
      System.out.println("=== " + fp.relativePath() + " ===");
      String content = fp.patchedContent();
      String[] lines = content.split("\n", -1);
      for (int i = 0; i < lines.length; i++) {
        System.out.println(i + ": [" + lines[i] + "]");
      }
    }
    System.out.println("=== DIFF ===");
    System.out.println(patch.unifiedDiff());
  }
}
