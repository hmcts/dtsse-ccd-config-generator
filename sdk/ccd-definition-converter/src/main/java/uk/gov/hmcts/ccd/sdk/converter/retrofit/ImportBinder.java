package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks the simple names already bound in one compilation unit (existing imports plus imports the
 * patch adds) and decides, for each type a synthesised field references, whether it can be written by
 * simple name (adding an import) or must be written fully-qualified because that simple name is
 * already bound to a <em>different</em> fully-qualified type.
 *
 * <p>Without this the patch adds a second {@code import a.b.Foo;} to a file that already imports
 * {@code c.d.Foo}, which is a compile error ({@code a type with the same simple name is already
 * defined by the single-type-import}) and its downstream {@code reference to Foo is ambiguous} — the
 * prl {@code OtherDocuments}/{@code Miam} and fpl {@code Document} clashes (findings C1 / F2). Binding
 * the first FQN per simple name and fully-qualifying every later conflicting one keeps every
 * reference unambiguous.
 */
final class ImportBinder {

  /** Simple name → the fully-qualified name bound to it in this compilation unit. */
  private final Map<String, String> boundBySimpleName = new LinkedHashMap<>();
  /** Imports the patch must add (excludes ones already present and conflicting ones qualified inline). */
  private final Set<String> addedImports = new LinkedHashSet<>();

  /**
   * Seeds the binder with the compilation unit's existing single-type imports.
   *
   * @param existingImports simple name → fully-qualified name already imported in the file
   */
  ImportBinder(Map<String, String> existingImports) {
    boundBySimpleName.putAll(existingImports);
  }

  /**
   * Resolves how to reference a type by its fully-qualified name in this compilation unit: its simple
   * name when that name is free or already bound to this same FQN (registering the import if new), or
   * the fully-qualified name when the simple name is already bound to a different type.
   *
   * @param fqn the fully-qualified type name to reference
   * @return the reference to write in source (simple name or fully-qualified)
   */
  String reference(String fqn) {
    int lastDot = fqn.lastIndexOf('.');
    if (lastDot < 0) {
      return fqn;
    }
    String simple = fqn.substring(lastDot + 1);
    String bound = boundBySimpleName.get(simple);
    if (bound == null) {
      boundBySimpleName.put(simple, fqn);
      addedImports.add("import " + fqn + ";");
      return simple;
    }
    if (bound.equals(fqn)) {
      return simple; // already imported (existing or added earlier) to the same type
    }
    // Simple name is taken by a different type — qualify this reference fully, add no import.
    return fqn;
  }

  /** The imports the patch should add for the references bound by simple name. */
  Set<String> addedImports() {
    return addedImports;
  }
}
