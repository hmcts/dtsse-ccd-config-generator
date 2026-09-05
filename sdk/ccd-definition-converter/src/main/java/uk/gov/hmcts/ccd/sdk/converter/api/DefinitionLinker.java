package uk.gov.hmcts.ccd.sdk.converter.api;

import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;

/**
 * Turns the raw IR into the semantic model for one case type.
 *
 * <p>Responsibilities: case type selection, state/role/fixed-list/complex-type inference,
 * event and page assembly, access-class derivation from AuthorisationCaseField (accounting
 * for the grants the SDK generator injects automatically), overlay variant resolution, type
 * mapping (Java type or typeOverride per field), and routing everything inexpressible to
 * passthrough sheets with a gap entry.
 */
public interface DefinitionLinker {

  /**
   * Links the IR into a case type model.
   *
   * @param ir the definition IR
   * @param options the conversion configuration
   * @param gaps collector for inexpressible findings
   * @return the linked model
   */
  CaseTypeModel link(DefinitionIr ir, ConversionOptions options, GapCollector gaps);
}
