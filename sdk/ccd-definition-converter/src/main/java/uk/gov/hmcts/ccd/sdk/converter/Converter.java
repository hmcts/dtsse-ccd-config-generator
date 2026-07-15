package uk.gov.hmcts.ccd.sdk.converter;

import com.palantir.javapoet.JavaFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import lombok.Builder;
import lombok.Value;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.api.DefinitionLinker;
import uk.gov.hmcts.ccd.sdk.converter.api.DefinitionReader;
import uk.gov.hmcts.ccd.sdk.converter.api.EmitContext;
import uk.gov.hmcts.ccd.sdk.converter.api.ReportWriter;
import uk.gov.hmcts.ccd.sdk.converter.api.SourceEmitter;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapEntry;

/**
 * The conversion pipeline: read JSON sheets, link into a semantic model, emit Java sources,
 * then write passthrough JSON and reports.
 */
@Builder(toBuilder = true)
public class Converter {

  private final DefinitionReader reader;
  private final DefinitionLinker linker;
  private final List<SourceEmitter> emitters;
  private final ReportWriter reportWriter;

  /**
   * Optional transform applied to the linked model before emission. Retrofit mode uses it to rebind
   * the model's getters/fields onto the team's existing classes (see {@code RetrofitModelRebinder}).
   * Null / identity in generate mode.
   */
  private final UnaryOperator<CaseTypeModel> modelTransform;

  /**
   * Runs the full conversion.
   *
   * @param options the parsed CLI configuration
   * @return the emitted files and gap findings
   * @throws ConversionException when blocking gaps are found and --allow-gaps is not set
   */
  public ConversionResult convert(ConversionOptions options) {
    GapCollector gaps = new GapCollector();

    DefinitionIr ir = reader.read(options, gaps);
    CaseTypeModel model = linker.link(ir, options, gaps);
    if (modelTransform != null) {
      model = modelTransform.apply(model);
    }

    EmitContext context = EmitContext.builder()
        .options(options)
        .gaps(gaps)
        .build();

    List<JavaFile> files = new ArrayList<>();
    for (SourceEmitter emitter : emitters) {
      files.addAll(emitter.emit(model, context));
    }

    if (gaps.hasBlockingGaps() && !options.isAllowGaps()) {
      reportWriter.write(model, gaps, options);
      throw new ConversionException(
          "Conversion found gaps that could not be expressed or passed through; "
              + "see the gap report under " + options.getReportDir()
              + " or re-run with --allow-gaps.");
    }

    for (JavaFile file : files) {
      try {
        file.writeTo(options.getOutputSrc());
      } catch (IOException e) {
        throw new UncheckedIOException("Failed writing generated source to "
            + options.getOutputSrc(), e);
      }
    }
    reportWriter.write(model, gaps, options);

    return ConversionResult.builder()
        .model(model)
        .files(files)
        .gaps(gaps.getEntries())
        .build();
  }

  @Value
  @Builder
  public static class ConversionResult {
    CaseTypeModel model;
    List<JavaFile> files;
    List<GapEntry> gaps;
  }

  public static class ConversionException extends RuntimeException {
    public ConversionException(String message) {
      super(message);
    }
  }
}
