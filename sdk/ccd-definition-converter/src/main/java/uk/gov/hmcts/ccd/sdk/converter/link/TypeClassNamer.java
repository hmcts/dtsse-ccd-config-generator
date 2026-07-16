package uk.gov.hmcts.ccd.sdk.converter.link;

import java.util.Set;

/**
 * Derives Java-conventional (PascalCase) class names for generated complex-type and fixed-list
 * companion types from their CCD definition IDs, and allocates them collision-free against a shared
 * used-name set.
 *
 * <p>The generated class name is decoupled from the wire ID: the CCD ComplexTypes / FixedLists ID a
 * field references is preserved verbatim on the type via {@code @ComplexType(name = "<id>")} (and,
 * for enums, the SDK's generators read that same {@code name} for the emitted list ID and every
 * referencing field's {@code FieldTypeParameter}), so renaming the class to PascalCase round-trips
 * byte-identically. This turns machine-shaped companions like {@code class benefit},
 * {@code enum FL_comparedToDWP} into {@code class Benefit}, {@code enum ComparedToDWP} — matching
 * every hand-written HMCTS model — without touching any CCD-facing identifier.
 */
final class TypeClassNamer {

  /**
   * The machine prefix sscs (and similar teams) stamp on fixed-list IDs; dropped from the derived
   * enum name (the ID itself is preserved as the wire ID), so {@code FL_comparedToDWP} yields the
   * enum {@code ComparedToDWP} rather than {@code FLComparedToDWP}.
   */
  private static final String FIXED_LIST_MACHINE_PREFIX = "FL_";

  private TypeClassNamer() {
  }

  /**
   * The PascalCase class name for a complex-type ID: split on non-alphanumeric runs and upper-case
   * the first letter of each run (so {@code otherPartySelection} → {@code OtherPartySelection},
   * {@code benefit} → {@code Benefit}).
   *
   * @param id the ComplexTypes sheet ID
   * @return the PascalCase Java class name (before collision resolution)
   */
  static String complexTypeName(String id) {
    return pascalCase(id);
  }

  /**
   * The PascalCase enum name for a fixed-list ID: strips a leading {@value #FIXED_LIST_MACHINE_PREFIX}
   * machine prefix when doing so still leaves a usable name, then PascalCases (so
   * {@code FL_comparedToDWP} → {@code ComparedToDWP}, {@code postponementReason} →
   * {@code PostponementReason}). A well-formed domain ID (nfdiv-style {@code ApplicationType}) is
   * returned unchanged.
   *
   * @param id the FixedLists sheet ID
   * @return the PascalCase Java enum name (before collision resolution)
   */
  static String fixedListName(String id) {
    if (id != null && id.startsWith(FIXED_LIST_MACHINE_PREFIX)) {
      String stripped = id.substring(FIXED_LIST_MACHINE_PREFIX.length());
      String candidate = pascalCase(stripped);
      // Only drop the prefix when the remainder yields a legal, non-empty identifier; a degenerate
      // ID like "FL_" or "FL_1" falls back to PascalCasing the whole ID.
      if (!candidate.isEmpty() && Character.isJavaIdentifierStart(candidate.charAt(0))) {
        return candidate;
      }
    }
    return pascalCase(id);
  }

  /**
   * Reserves {@code base} in {@code used}, appending the smallest numeric suffix (from 2) that makes
   * it unique when it is already taken, and records the chosen name. Deterministic given a
   * deterministic allocation order, so the same definition yields the same class names across runs.
   *
   * @param base the desired PascalCase name
   * @param used the running set of already-allocated class names (mutated)
   * @return the unique class name allocated
   */
  static String allocate(String base, Set<String> used) {
    String safe = base.isEmpty() ? "Type" : base;
    if (used.add(safe)) {
      return safe;
    }
    int suffix = 2;
    String candidate = safe + suffix;
    while (!used.add(candidate)) {
      candidate = safe + (++suffix);
    }
    return candidate;
  }

  private static String pascalCase(String id) {
    if (id == null || id.isEmpty()) {
      return "";
    }
    String[] parts = id.split("[^a-zA-Z0-9]+");
    StringBuilder sb = new StringBuilder();
    for (String part : parts) {
      if (!part.isEmpty()) {
        sb.append(Character.toUpperCase(part.charAt(0)));
        if (part.length() > 1) {
          sb.append(part.substring(1));
        }
      }
    }
    return sb.toString();
  }
}
