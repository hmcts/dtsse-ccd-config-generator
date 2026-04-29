package uk.gov.hmcts.ccd.sdk.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.collect.Lists;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import uk.gov.hmcts.ccd.sdk.type.DynamicList;
import uk.gov.hmcts.ccd.sdk.type.DynamicListElement;

public class AnswerBuilder<U, T, S, R extends HasRole> {

  private final ChallengeQuestionField.QuestionBuilder<T, S, R> parent;
  private final Class<U> currentType;
  private final PropertyUtils propertyUtils;
  private final List<String> path;
  private final List<R> roles;
  private final String pendingPrefix;
  private final boolean appendToLastAnswer;

  AnswerBuilder(ChallengeQuestionField.QuestionBuilder<T, S, R> parent,
                Class<U> currentType,
                PropertyUtils propertyUtils,
                List<R> roles,
                boolean appendToLastAnswer) {
    this(parent, currentType, propertyUtils, roles, appendToLastAnswer,
        Lists.newArrayList(), "");
  }

  private AnswerBuilder(ChallengeQuestionField.QuestionBuilder<T, S, R> parent,
                        Class<U> currentType,
                        PropertyUtils propertyUtils,
                        List<R> roles,
                        boolean appendToLastAnswer,
                        List<String> path,
                        String pendingPrefix) {
    this.parent = parent;
    this.currentType = currentType;
    this.propertyUtils = propertyUtils;
    this.roles = roles;
    this.appendToLastAnswer = appendToLastAnswer;
    this.path = path;
    this.pendingPrefix = pendingPrefix;
  }

  public <V> AnswerBuilder<V, T, S, R> complex(TypedPropertyGetter<U, V> getter) {
    JsonUnwrapped unwrapped = propertyUtils.getAnnotationOfProperty(
        currentType, getter, JsonUnwrapped.class);
    Class<V> nextType = propertyUtils.getPropertyType(currentType, getter);
    if (unwrapped != null) {
      String newPrefix = pendingPrefix.isEmpty()
          ? unwrapped.prefix()
          : pendingPrefix + StringUtils.capitalize(unwrapped.prefix());
      return new AnswerBuilder<>(parent, nextType, propertyUtils, roles,
          appendToLastAnswer, path, newPrefix);
    }
    String name = applyPrefix(propertyUtils.getPropertyName(currentType, getter));
    List<String> nextPath = Lists.newArrayList(path);
    nextPath.add(name);
    return new AnswerBuilder<>(parent, nextType, propertyUtils, roles,
        appendToLastAnswer, nextPath, "");
  }

  public ChallengeQuestionField.OrBuilder<T, S, R> field(TypedPropertyGetter<U, ?> getter) {
    String name = applyPrefix(propertyUtils.getPropertyName(currentType, getter));
    path.add(name);
    return finish();
  }

  public ChallengeQuestionField.OrBuilder<T, S, R> field(String literal) {
    path.add(literal);
    return finish();
  }

  public ChallengeQuestionField.OrBuilder<T, S, R> selectedLabelOf(
      TypedPropertyGetter<U, DynamicList> getter) {
    appendDynamicPath(getter, true);
    return finish();
  }

  public ChallengeQuestionField.OrBuilder<T, S, R> selectedValueOf(
      TypedPropertyGetter<U, DynamicList> getter) {
    appendDynamicPath(getter, false);
    return finish();
  }

  private void appendDynamicPath(TypedPropertyGetter<U, DynamicList> getter, boolean labelNotCode) {
    String name = applyPrefix(propertyUtils.getPropertyName(currentType, getter));
    path.add(name);
    path.add(propertyUtils.getPropertyName(DynamicList.class, DynamicList::getValue));
    path.add(propertyUtils.getPropertyName(DynamicListElement.class,
        labelNotCode ? DynamicListElement::getLabel : DynamicListElement::getCode));
  }

  private String applyPrefix(String name) {
    return pendingPrefix.isEmpty() ? name : pendingPrefix + StringUtils.capitalize(name);
  }

  private ChallengeQuestionField.OrBuilder<T, S, R> finish() {
    if (appendToLastAnswer) {
      parent.appendAlternative(path);
    } else {
      parent.addAnswer(path, roles);
    }
    return new ChallengeQuestionField.OrBuilder<>(parent, roles);
  }
}
