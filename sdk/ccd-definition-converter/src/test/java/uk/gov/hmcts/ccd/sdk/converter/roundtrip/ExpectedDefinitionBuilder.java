package uk.gov.hmcts.ccd.sdk.converter.roundtrip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.ir.Columns;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetName;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetRow;
import uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition;
import uk.gov.hmcts.ccd.sdk.converter.reader.Substitutor;

/**
 * Builds the "expected" side of a round-trip comparison: the input definition aggregated
 * per sheet for a given environment, mirroring what {@code json2xlsx} would assemble.
 *
 * <p>For an environment combination, an overlay-tagged row is kept only when every one of its
 * tags maps to a configured predicate that is active; base rows are always kept. Environment
 * placeholders are then substituted using the supplied environment map, and rows are grouped by
 * the sheet's definition-store name so they line up with the generated output aggregated by
 * {@code NormalisingCcdConfigComparator.aggregateDirectory}.
 */
final class ExpectedDefinitionBuilder {

  /**
   * Callback URL columns that must NOT be placeholder-substituted on the expected side. The
   * converter emits no callback wiring and instead carries these columns through verbatim (see
   * {@code DefaultDefinitionLinker}), so the actual side holds the raw {@code ${CCD_DEF_*}} value.
   * The expected side must match byte-for-byte, so it keeps the raw value too. (Substituting them
   * was only ever needed when forward-mode compared the generated URL post-substitution; with no
   * generated URL to compare against, substitution here would spuriously diverge the two sides.)
   * Every other string column is substituted exactly as {@code json2xlsx} would at build time.
   */
  private static final Set<String> RAW_CALLBACK_COLUMNS = Set.of(
      Columns.CALLBACK_URL_ABOUT_TO_START_EVENT,
      Columns.CALLBACK_URL_ABOUT_TO_SUBMIT_EVENT,
      Columns.CALLBACK_URL_SUBMITTED_EVENT,
      Columns.CALLBACK_URL_MID_EVENT);

  private ExpectedDefinitionBuilder() {
  }

  /**
   * Aggregates the IR for one case type and environment into per-sheet rows.
   *
   * @param ir the parsed input definition
   * @param caseTypeId the case type being compared
   * @param options the conversion options (supplies the overlay suffix predicates)
   * @param env the environment map for placeholder substitution and overlay evaluation
   * @return sheet name to substituted rows for the active environment
   */
  static Map<String, List<Map<String, Object>>> build(
      DefinitionIr ir,
      String caseTypeId,
      ConversionOptions options,
      Map<String, String> env) {
    Map<String, List<Map<String, Object>>> sheets = new LinkedHashMap<>();
    for (SheetName sheet : SheetName.values()) {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (SheetRow row : ir.rowsForCaseType(sheet, caseTypeId)) {
        // The CaseType sheet keys the case type by its own ID column (not a CaseTypeID column), so
        // rowsForCaseType cannot filter it and returns every sibling case type the input declares
        // (ET ships ET_EnglandWales alongside _Listings/_Multiple). The generator only emits the
        // converted case type's row, so keep only that row on the expected side too.
        if (sheet == SheetName.CASE_TYPE
            && !caseTypeId.equals(row.getString(uk.gov.hmcts.ccd.sdk.converter.ir.Columns.ID)
                .orElse(null))) {
          continue;
        }
        if (isActive(row, options, env)) {
          rows.add(new LinkedHashMap<>(row.getColumns()));
        }
      }
      List<Map<String, Object>> substituted = Substitutor.injectEnvironmentVariables(env, rows);
      // Restore the raw (un-substituted) callback URL columns: the converter carries them through
      // verbatim, so both sides must hold the original ${CCD_DEF_*} value (see RAW_CALLBACK_COLUMNS).
      for (int i = 0; i < substituted.size(); i++) {
        Map<String, Object> original = rows.get(i);
        Map<String, Object> out = substituted.get(i);
        for (String column : RAW_CALLBACK_COLUMNS) {
          if (original.containsKey(column)) {
            out.put(column, original.get(column));
          }
        }
      }
      if (!substituted.isEmpty()) {
        sheets.put(sheet.getName(), substituted);
      }
    }
    return sheets;
  }

  private static boolean isActive(
      SheetRow row, ConversionOptions options, Map<String, String> env) {
    if (row.isBase()) {
      return true;
    }
    for (String tag : row.getOverlayTags()) {
      OverlayCondition condition = options.getOverlaySuffixes().get(tag);
      if (condition == null || !condition.isActive(env)) {
        return false;
      }
    }
    return true;
  }
}
