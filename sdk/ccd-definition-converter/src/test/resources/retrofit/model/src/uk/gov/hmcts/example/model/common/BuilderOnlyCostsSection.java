package uk.gov.hmcts.example.model.common;

/**
 * A subclass calling {@link BuilderOnlyCosts}'s INFERRED all-args constructor positionally, which is
 * what makes that class's definition-only member unrepairable: neither widening (there is no source
 * constructor) nor adding a narrow one (it would suppress Lombok's inference) is safe.
 */
public class BuilderOnlyCostsSection extends BuilderOnlyCosts {

  private String capText;

  public BuilderOnlyCostsSection(String cap, String note, String capText) {
    super(cap, note);
    this.capText = capText;
  }
}
