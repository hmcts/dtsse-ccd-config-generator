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

  /** The FixedLists sheet ID — also the generated enum's simple name. */
  String id;

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
