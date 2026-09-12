package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * A complex type nested inside {@link EventComplexCollectionParty} through a <em>second</em>
 * collection ({@code List<ListValue<EventComplexCollectionChild>>}). Reached in
 * {@link EventComplexCollectionCaseType} via the element-typed
 * {@code .complex(getter, EventComplexCollectionChild.class)} scope opened on the nested collection,
 * proving that overload composes on a nested builder. Its member carries a declared {@code @CCD(hint)}
 * left unset in the event scope, so the row's {@code HintText} comes from the cascade.
 */
@Data
@ComplexType(name = "EventComplexCollectionChild", generate = true)
public class EventComplexCollectionChild {

  @CCD(label = "Child name", hint = "The child's full name")
  private String childName;
}
