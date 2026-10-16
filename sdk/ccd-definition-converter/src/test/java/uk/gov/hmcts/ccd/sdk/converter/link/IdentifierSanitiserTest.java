package uk.gov.hmcts.ccd.sdk.converter.link;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdentifierSanitiserTest {

  @Test
  void legalIdentifierIsRecognised() {
    assertThat(IdentifierSanitiser.isLegalIdentifier("applicantName")).isTrue();
    assertThat(IdentifierSanitiser.isLegalIdentifier("Open")).isTrue();
  }

  @Test
  void illegalIdentifiersAreRejected() {
    assertThat(IdentifierSanitiser.isLegalIdentifier("1State")).isFalse();
    assertThat(IdentifierSanitiser.isLegalIdentifier("case-worker")).isFalse();
    assertThat(IdentifierSanitiser.isLegalIdentifier("")).isFalse();
    assertThat(IdentifierSanitiser.isLegalIdentifier("class")).isFalse();
  }

  @Test
  void memberNameKeepsLegalValues() {
    assertThat(IdentifierSanitiser.toMemberName("applicantName")).isEqualTo("applicantName");
  }

  @Test
  void memberNameSanitisesIllegalValues() {
    assertThat(IdentifierSanitiser.toMemberName("case-worker")).isEqualTo("case_worker");
    assertThat(IdentifierSanitiser.toMemberName("1field")).isEqualTo("_1field");
    assertThat(IdentifierSanitiser.toMemberName("class")).isEqualTo("class_");
  }

  @Test
  void constantNameSplitsCamelAndPunctuation() {
    assertThat(IdentifierSanitiser.toConstantName("caseworker-ia")).isEqualTo("CASEWORKER_IA");
    assertThat(IdentifierSanitiser.toConstantName("possession")).isEqualTo("POSSESSION");
    assertThat(IdentifierSanitiser.toConstantName("myClaimType")).isEqualTo("MY_CLAIM_TYPE");
    assertThat(IdentifierSanitiser.toConstantName("CREATOR")).isEqualTo("CREATOR");
  }

  @Test
  void constantNameHandlesLeadingDigit() {
    assertThat(IdentifierSanitiser.toConstantName("1st")).isEqualTo("_1ST");
  }
}
