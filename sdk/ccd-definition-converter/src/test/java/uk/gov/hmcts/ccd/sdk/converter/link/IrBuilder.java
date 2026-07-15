package uk.gov.hmcts.ccd.sdk.converter.link;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetName;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetRow;

/**
 * Fluent builder for hand-constructed {@link DefinitionIr} instances used by the link tests.
 */
final class IrBuilder {

  private final ListMultimap<SheetName, SheetRow> rows = LinkedListMultimap.create();

  static IrBuilder builder() {
    return new IrBuilder();
  }

  IrBuilder row(SheetName sheet, Map<String, Object> columns) {
    return row(sheet, columns, Set.of());
  }

  IrBuilder row(SheetName sheet, Map<String, Object> columns, Set<String> overlayTags) {
    rows.put(sheet, SheetRow.builder()
        .sheet(sheet)
        .columns(columns)
        .overlayTags(overlayTags)
        .source(Path.of("test", sheet.getName() + ".json"))
        .build());
    return this;
  }

  /**
   * Adds a row with an explicit source path, so tests can exercise the cross-file collision
   * detection that keys off {@link SheetRow#getSource()}.
   */
  IrBuilder row(SheetName sheet, Map<String, Object> columns, Path source) {
    rows.put(sheet, SheetRow.builder()
        .sheet(sheet)
        .columns(columns)
        .overlayTags(Set.of())
        .source(source)
        .build());
    return this;
  }

  DefinitionIr build() {
    return new DefinitionIr(rows);
  }

  static Map<String, Object> cols(Object... keyValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put((String) keyValues[i], keyValues[i + 1]);
    }
    return map;
  }
}
