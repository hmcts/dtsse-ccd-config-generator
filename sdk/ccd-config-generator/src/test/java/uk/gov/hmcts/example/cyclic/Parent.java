package uk.gov.hmcts.example.cyclic;

import lombok.Data;

/** One half of a mutually-referencing complex-type pair. */
@Data
public class Parent {

  private String name;

  private Child child;
}
