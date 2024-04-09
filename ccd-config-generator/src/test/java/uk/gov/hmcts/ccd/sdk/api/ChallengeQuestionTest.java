package uk.gov.hmcts.ccd.sdk.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.Test;

public class ChallengeQuestionTest {
  @Test
  public void testDefaultValues() {
    ChallengeQuestion question = ChallengeQuestion.builder()
      .displayOrder(1)
      .questionText("Test Question")
      .answerFieldType("Text")
      .answer("Test Answer")
      .questionId("123")
      .caseTypeID("456")
      .build();

    assertEquals("01/04/24", question.getLiveFrom());
    assertEquals("NoCChallenge", question.getId());
    assertEquals(1, question.getDisplayOrder());
    assertEquals("Test Question", question.getQuestionText());
    assertEquals("Text", question.getAnswerFieldType());
    assertEquals("Test Answer", question.getAnswer());
    assertEquals("123", question.getQuestionId());
    assertEquals("456", question.getCaseTypeID());
  }
}
