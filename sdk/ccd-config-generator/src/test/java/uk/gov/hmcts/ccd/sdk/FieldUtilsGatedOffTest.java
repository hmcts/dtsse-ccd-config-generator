package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * {@link FieldUtils#gatedOffFieldIds} must report every CCD field ID the generators drop, so the
 * by-ID placement loops (CaseEventToFields, CaseTypeTab, search, AuthorisationCaseField) skip them
 * too. A gated-off {@code @JsonUnwrapped} container drops ALL of its members from CaseField
 * ({@link FieldUtils#getCaseFields} filters the container out), so each member's prefixed ID is
 * gated off with it.
 */
public class FieldUtilsGatedOffTest {

  static class Inner {
    private String beta;
  }

  static class Container {
    private String alpha;

    @JsonUnwrapped(prefix = "deep")
    private Inner inner;
  }

  static class CaseData {
    private String plain;

    @JsonUnwrapped(prefix = "grp")
    @CCD(gate = "CCD_DEF_GATED_OFF_TEST:true")
    private Container grp;

    @JsonUnwrapped(prefix = "open")
    private Container open;
  }

  @Test
  public void gatedOffUnwrappedContainerGatesOffEveryMember() {
    System.clearProperty("CCD_DEF_GATED_OFF_TEST");

    assertThat(FieldUtils.gatedOffFieldIds(CaseData.class))
        .containsExactlyInAnyOrder("grpAlpha", "grpDeepBeta");
  }

  @Test
  public void activeGateOnUnwrappedContainerGatesOffNothing() {
    System.setProperty("CCD_DEF_GATED_OFF_TEST", "true");
    try {
      assertThat(FieldUtils.gatedOffFieldIds(CaseData.class)).isEmpty();
    } finally {
      System.clearProperty("CCD_DEF_GATED_OFF_TEST");
    }
  }
}
