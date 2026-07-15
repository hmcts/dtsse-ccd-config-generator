package uk.gov.hmcts.ccd.sdk.converter.api;

import com.palantir.javapoet.JavaFile;
import java.util.List;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;

/**
 * Emits generated Java sources from the linked model. Implementations cover disjoint
 * concerns (model classes, config classes) and are run in sequence by the
 * converter; each returns the files it produced.
 */
public interface SourceEmitter {

  /**
   * Generates source files for its concern.
   *
   * @param model the linked case type model
   * @param context packages and options
   * @return the generated files (may be empty)
   */
  List<JavaFile> emit(CaseTypeModel model, EmitContext context);
}
