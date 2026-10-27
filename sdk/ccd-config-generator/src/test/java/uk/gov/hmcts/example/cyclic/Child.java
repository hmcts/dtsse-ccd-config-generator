package uk.gov.hmcts.example.cyclic;

import lombok.Data;

/** The other half of the pair — {@code Parent -> Child -> Parent} closes the cycle. */
@Data
public class Child {

  private String name;

  private Parent parent;
}
