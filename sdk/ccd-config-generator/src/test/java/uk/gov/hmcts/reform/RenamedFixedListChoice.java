package uk.gov.hmcts.reform;

import lombok.AllArgsConstructor;
import lombok.Getter;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.api.HasLabel;

/**
 * A generated fixed list whose Java class name is Java-conventional PascalCase but whose CCD-facing
 * FixedLists ID is carried separately via {@code @ComplexType(name)}. This is the shape the
 * ccd-definition-converter emits when it drops a machine {@code FL_} prefix (finding #4): the class
 * is renamed for readability while the original list ID round-trips as the wire ID.
 *
 * <p>{@code generate = true} keeps the enum on the FixedLists generation path (FixedListGenerator
 * only treats an enum as a generated list when it has no {@code @ComplexType} or one with
 * {@code generate() == true}); {@code name} supplies the emitted list ID and every referencing
 * field's {@code FieldTypeParameter}.
 */
@Getter
@AllArgsConstructor
@ComplexType(name = "FL_renamedChoice", generate = true)
public enum RenamedFixedListChoice implements HasLabel {
  HIGHER("Higher"),
  SAME("Same"),
  LOWER("Lower");

  private final String label;
}
