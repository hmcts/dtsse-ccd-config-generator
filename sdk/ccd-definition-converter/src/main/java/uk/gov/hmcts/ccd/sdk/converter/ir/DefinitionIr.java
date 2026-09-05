package uk.gov.hmcts.ccd.sdk.converter.ir;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ListMultimap;
import java.util.List;
import java.util.function.Predicate;

/**
 * The intermediate representation of a JSON CCD definition: every row of every sheet found
 * under the input directories, before any semantic interpretation.
 *
 * <p>Rows keep their raw columns and overlay tags; filtering by case type or overlay happens
 * downstream in the linker. Sheet fragment files are already aggregated — the IR carries no
 * trace of the on-disk file layout beyond each row's {@code source} (kept for diagnostics).
 */
public class DefinitionIr {

  private final ListMultimap<SheetName, SheetRow> rows;

  public DefinitionIr(ListMultimap<SheetName, SheetRow> rows) {
    this.rows = ImmutableListMultimap.copyOf(rows);
  }

  public List<SheetRow> rows(SheetName sheet) {
    return rows.get(sheet);
  }

  public List<SheetRow> rows(SheetName sheet, Predicate<SheetRow> filter) {
    return rows.get(sheet).stream().filter(filter).toList();
  }

  public boolean hasSheet(SheetName sheet) {
    return !rows.get(sheet).isEmpty();
  }

  /**
   * Rows of a sheet belonging to a case type, using the sheet's CaseTypeID column. Sheets
   * without a CaseTypeID column (Jurisdiction, FixedLists, ComplexTypes in some layouts)
   * return all rows.
   *
   * <p>The case-type column header is matched case-insensitively ({@code CaseTypeID} vs
   * {@code CaseTypeId}): the importer treats them as the same column, and a single real
   * definition can ship both spellings across its sheets (ET's {@code AuthorisationCaseField} uses
   * {@code CaseTypeId} while its {@code CaseField} uses {@code CaseTypeID}). Reading only the
   * canonical spelling would leave the lower-case-d sheets unfiltered, silently pulling in every
   * sibling case type's rows (e.g. {@code ET_EnglandWales_Multiple} into {@code ET_EnglandWales}).
   *
   * @param sheet the sheet to read
   * @param caseTypeId the case type to filter by
   * @return rows for the case type, or all rows when the sheet carries no CaseTypeID
   */
  public List<SheetRow> rowsForCaseType(SheetName sheet, String caseTypeId) {
    return rows.get(sheet).stream()
        .filter(r -> r.getString(Columns.CASE_TYPE_ID, Columns.CASE_TYPE_ID_LOWER)
            .map(caseTypeId::equals).orElse(true))
        .toList();
  }
}
