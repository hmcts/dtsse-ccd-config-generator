package uk.gov.hmcts.ccd.sdk.converter;

import java.util.List;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.api.SourceEmitter;
import uk.gov.hmcts.ccd.sdk.converter.emit.config.ApplicationEmitter;
import uk.gov.hmcts.ccd.sdk.converter.emit.config.CoreConfigEmitter;
import uk.gov.hmcts.ccd.sdk.converter.emit.config.EnvironmentFlagsEmitter;
import uk.gov.hmcts.ccd.sdk.converter.emit.config.EventsConfigEmitter;
import uk.gov.hmcts.ccd.sdk.converter.emit.model.AccessClassEmitter;
import uk.gov.hmcts.ccd.sdk.converter.emit.model.CaseDataEmitter;
import uk.gov.hmcts.ccd.sdk.converter.emit.model.ComplexTypeEmitter;
import uk.gov.hmcts.ccd.sdk.converter.emit.model.EnumEmitter;
import uk.gov.hmcts.ccd.sdk.converter.link.DefaultDefinitionLinker;
import uk.gov.hmcts.ccd.sdk.converter.reader.JsonDefinitionReader;
import uk.gov.hmcts.ccd.sdk.converter.report.GapAndPassthroughWriter;

/**
 * Assembles the converter pipeline from its component implementations.
 *
 * <p>Emitters run in dependency order: model classes (CaseData, complex types, enums, access
 * classes) and the environment-flags helper before the config classes that reference them.
 */
public final class ConverterFactory {

  private ConverterFactory() {
  }

  /**
   * Builds the pipeline for the given options.
   *
   * @param options the parsed CLI configuration
   * @return the assembled converter
   */
  public static Converter create(ConversionOptions options) {
    return create(options, List.of());
  }

  /**
   * Builds the pipeline, appending {@code extraEmitters} after the standard ones. Retrofit mode uses
   * this to add its companion complex-type emitter (which needs the parsed model index, not
   * available to this static factory).
   *
   * @param options the parsed CLI configuration
   * @param extraEmitters additional emitters to append after the standard set
   * @return the assembled converter
   */
  public static Converter create(ConversionOptions options, List<SourceEmitter> extraEmitters) {
    List<SourceEmitter> emitters;
    if (options.isRetrofit()) {
      // Retrofit mode annotates the team's EXISTING model, so the model-class emitters (CaseData,
      // complex types) are dropped here — those classes belong to the team and are patched, not
      // regenerated. RetrofitConverter appends a RetrofitComplexTypeEmitter for the DEFINITION-ONLY
      // complex types (those with no existing model class — e.g. Civil's SDO composites), which
      // needs the parsed model index and so is wired there rather than in this static factory.
      // Everything else is still generated as companion sources: enums (fresh State/UserRole/
      // FixedLists, minus any reused model enum), access classes, EnvironmentFlags, and the config
      // classes (retargeted at the team's model via EmitContext).
      emitters = List.of(
          new EnumEmitter(),
          new AccessClassEmitter(),
          new EnvironmentFlagsEmitter(),
          new CoreConfigEmitter(),
          new EventsConfigEmitter(),
          new ApplicationEmitter());
    } else {
      emitters = List.of(
          new CaseDataEmitter(),
          new ComplexTypeEmitter(),
          new EnumEmitter(),
          new AccessClassEmitter(),
          new EnvironmentFlagsEmitter(),
          new CoreConfigEmitter(),
          new EventsConfigEmitter(),
          new ApplicationEmitter());
    }

    List<SourceEmitter> allEmitters = new java.util.ArrayList<>(emitters);
    allEmitters.addAll(extraEmitters);

    return Converter.builder()
        .reader(new JsonDefinitionReader())
        .linker(new DefaultDefinitionLinker())
        .emitters(allEmitters)
        .reportWriter(new GapAndPassthroughWriter())
        .build();
  }
}
