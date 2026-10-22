package uk.gov.hmcts.example.model;

/**
 * Superclass of {@link CaseData}, exercising rule 4: the resolver walks the {@code extends} chain
 * so a field declared here ({@code caseReference}) resolves as a CaseData property.
 */
public class BaseCaseData {

  // Rule 4: superclass field -> id "caseReference" (String -> Text, EXACT).
  private String caseReference;
}
