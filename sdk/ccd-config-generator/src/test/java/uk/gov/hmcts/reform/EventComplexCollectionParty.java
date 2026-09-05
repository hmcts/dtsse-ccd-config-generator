package uk.gov.hmcts.reform;

import java.util.List;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.type.ListValue;

/**
 * The element type of the {@code parties} collection in {@link EventComplexCollectionCaseType}. Its
 * scalar members are placed per-event through the element-typed {@code .complex(getter, Class)} scope
 * opened on the collection; {@link #children} is a further nested collection reached through the same
 * overload on the nested builder.
 */
@Data
@ComplexType(name = "EventComplexCollectionParty", generate = true)
public class EventComplexCollectionParty {

  @CCD(label = "Party name")
  private String partyName;

  @CCD(label = "Party role", hint = "The declared role hint")
  private String role;

  @CCD(label = "Party reference", hint = "The declared reference hint")
  private String reference;

  @CCD(label = "Children")
  private List<ListValue<EventComplexCollectionChild>> children;
}
