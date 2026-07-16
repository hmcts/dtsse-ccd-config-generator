package uk.gov.hmcts.ccd.sdk.converter.model;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * A fixed list, destined for a generated enum implementing the SDK's HasLabel.
 *
 * <p>The generated enum's simple class name must equal the list ID — the SDK's
 * FixedListGenerator names the output file and FieldTypeParameter after the enum class.
 * Item codes that are not legal Java identifiers are sanitised into constant names with the
 * original code preserved via {@code @JsonValue}.
 */
@Value
@Builder
public class FixedListModel {

  /**
   * The FixedLists sheet ID — the CCD-facing list ID, preserved verbatim as the wire ID via
   * {@code @ComplexType(name = id)} (which the SDK's FixedListGenerator and CaseFieldGenerator read
   * for the emitted list ID and referencing fields' FieldTypeParameter) when it differs from
   * {@link #javaClassName}.
   */
  String id;

  /**
   * The generated enum's Java-conventional (PascalCase) simple name, with any machine {@code FL_}
   * prefix dropped. Decoupled from {@link #id} so {@code FL_comparedToDWP} yields the enum
   * {@code ComparedToDWP} while the wire ID round-trips via {@code @ComplexType(name = id)}. Equals
   * {@link #id} when the ID is already a well-formed enum name.
   */
  String javaClassName;

  List<Item> items;

  @Value
  @Builder
  public static class Item {

    /** ListElementCode — what CCD stores. */
    String code;

    /** ListElement — the display label, returned from getLabel(). */
    String label;

    /** The sanitised enum constant name; equals code when code is a legal identifier. */
    String javaConstant;

    /** DisplayOrder, used to order constants (the generator emits positional order). */
    Integer displayOrder;
  }
}
