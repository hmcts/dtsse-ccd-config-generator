package uk.gov.hmcts.ccd.sdk.converter.api;

import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;

/**
 * Reads a JSON CCD definition from disk into the intermediate representation.
 *
 * <p>Responsibilities: sheet discovery (flat files, {@code Sheet-<suffix>.json} overlay
 * files, and recursive fragment directories), fragment aggregation, overlay tagging against
 * the configured suffixes, and structural validation. Env-var placeholders in values are
 * preserved verbatim.
 */
public interface DefinitionReader {

  /**
   * Reads all sheets under the configured inputs.
   *
   * @param options the conversion configuration (inputs, overlay suffixes)
   * @param gaps collector for findings (e.g. unknown sheets)
   * @return the aggregated intermediate representation
   */
  DefinitionIr read(ConversionOptions options, GapCollector gaps);
}
