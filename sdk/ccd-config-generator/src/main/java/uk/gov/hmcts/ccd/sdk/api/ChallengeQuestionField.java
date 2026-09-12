package uk.gov.hmcts.ccd.sdk.api;

import com.google.common.collect.Lists;
import java.util.List;
import lombok.Data;

@Data
public class ChallengeQuestionField<R extends HasRole> {

  private final String questionId;
  private final String questionText;
  private int displayOrder;
  private String answerFieldType = "Text";
  private final List<AnswerMapping<R>> answers = Lists.newArrayList();

  public ChallengeQuestionField(String questionId, String questionText, int displayOrder) {
    this.questionId = questionId;
    this.questionText = questionText;
    this.displayOrder = displayOrder;
  }

  @Data
  public static class AnswerMapping<R extends HasRole> {
    private final List<String> pathSegments;
    private final List<R> roles;
    private final boolean useRoleAsDeclared;

    public AnswerMapping(List<String> pathSegments, List<R> roles) {
      this(pathSegments, roles, false);
    }

    public AnswerMapping(List<String> pathSegments, List<R> roles, boolean useRoleAsDeclared) {
      this.pathSegments = pathSegments;
      this.roles = roles;
      this.useRoleAsDeclared = useRoleAsDeclared;
    }
  }

  public static class QuestionBuilder<T, R extends HasRole> {
    private final ChallengeQuestion.ChallengeBuilder<T, R> parent;
    private final ChallengeQuestionField<R> question;
    private final Class<T> model;
    private final PropertyUtils propertyUtils;

    public QuestionBuilder(ChallengeQuestion.ChallengeBuilder<T, R> parent,
                           ChallengeQuestionField<R> question,
                           Class<T> model,
                           PropertyUtils propertyUtils) {
      this.parent = parent;
      this.question = question;
      this.model = model;
      this.propertyUtils = propertyUtils;
    }

    @SafeVarargs
    public final AnswerBuilder<T, T, R> answer(R... roles) {
      return new AnswerBuilder<>(this, model, propertyUtils, List.of(roles), false);
    }

    /**
     * Like {@link #answer} but emits roles exactly as declared (no auto-bracketing).
     * Use for Group Access / access-profile roles such as {@code defendant-solicitor}
     * once definition-store accepts unbracketed ChallengeQuestion Answers.
     */
    @SafeVarargs
    public final AnswerBuilder<T, T, R> answerAsDeclared(R... roles) {
      return new AnswerBuilder<>(this, model, propertyUtils, List.of(roles), true);
    }

    public QuestionBuilder<T, R> displayOrder(int order) {
      question.displayOrder = order;
      return this;
    }

    public QuestionBuilder<T, R> answerFieldType(String type) {
      question.answerFieldType = type;
      return this;
    }

    public ChallengeQuestion.ChallengeBuilder<T, R> done() {
      return parent;
    }

    void addAnswer(List<String> path, List<R> roles, boolean useRoleAsDeclared) {
      question.answers.add(new AnswerMapping<>(List.copyOf(path), List.copyOf(roles), useRoleAsDeclared));
    }
  }
}
