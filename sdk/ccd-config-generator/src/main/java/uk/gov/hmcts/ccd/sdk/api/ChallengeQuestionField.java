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
    private final List<List<String>> pathAlternatives;
    private final List<R> roles;
  }

  public static class QuestionBuilder<T, S, R extends HasRole> {
    private final ChallengeQuestion.ChallengeBuilder<T, S, R> parent;
    private final ChallengeQuestionField<R> question;
    private final Class<T> model;
    private final PropertyUtils propertyUtils;

    public QuestionBuilder(ChallengeQuestion.ChallengeBuilder<T, S, R> parent,
                           ChallengeQuestionField<R> question,
                           Class<T> model,
                           PropertyUtils propertyUtils) {
      this.parent = parent;
      this.question = question;
      this.model = model;
      this.propertyUtils = propertyUtils;
    }

    @SafeVarargs
    public final AnswerBuilder<T, T, S, R> answer(R... roles) {
      return new AnswerBuilder<>(this, model, propertyUtils, List.of(roles), false);
    }

    public QuestionBuilder<T, S, R> displayOrder(int order) {
      question.displayOrder = order;
      return this;
    }

    public QuestionBuilder<T, S, R> answerFieldType(String type) {
      question.answerFieldType = type;
      return this;
    }

    public ChallengeQuestion.ChallengeBuilder<T, S, R> done() {
      return parent;
    }

    void addAnswer(List<String> path, List<R> roles) {
      List<List<String>> alternatives = Lists.newArrayList();
      alternatives.add(List.copyOf(path));
      question.answers.add(new AnswerMapping<>(alternatives, List.copyOf(roles)));
    }

    void appendAlternative(List<String> path) {
      if (question.answers.isEmpty()) {
        throw new IllegalStateException("or(...) called before any .field(...) / .selectedLabelOf(...)");
      }
      question.answers.get(question.answers.size() - 1)
          .getPathAlternatives()
          .add(List.copyOf(path));
    }

    Class<T> model() {
      return model;
    }

    PropertyUtils propertyUtils() {
      return propertyUtils;
    }
  }

  public static class OrBuilder<T, S, R extends HasRole> {
    private final QuestionBuilder<T, S, R> parent;
    private final List<R> roles;

    OrBuilder(QuestionBuilder<T, S, R> parent, List<R> roles) {
      this.parent = parent;
      this.roles = roles;
    }

    public AnswerBuilder<T, T, S, R> or() {
      return new AnswerBuilder<>(parent, parent.model(), parent.propertyUtils(), roles, true);
    }

    public OrBuilder<T, S, R> or(TypedPropertyGetter<T, ?> getter) {
      return or().field(getter);
    }

    public OrBuilder<T, S, R> or(String literal) {
      return or().field(literal);
    }

    @SafeVarargs
    public final AnswerBuilder<T, T, S, R> answer(R... nextRoles) {
      return parent.answer(nextRoles);
    }

    public QuestionBuilder<T, S, R> displayOrder(int order) {
      return parent.displayOrder(order);
    }

    public QuestionBuilder<T, S, R> answerFieldType(String type) {
      return parent.answerFieldType(type);
    }

    public QuestionBuilder<T, S, R> question(String questionId, String questionText) {
      return parent.done().question(questionId, questionText);
    }

    public ChallengeQuestion.ChallengeBuilder<T, S, R> done() {
      return parent.done();
    }
  }
}
