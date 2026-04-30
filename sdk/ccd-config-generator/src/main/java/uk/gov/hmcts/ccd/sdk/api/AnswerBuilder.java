package uk.gov.hmcts.ccd.sdk.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.collect.Lists;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

public class AnswerBuilder<U, T, R extends HasRole> {

  private final ChallengeQuestionField.QuestionBuilder<T, R> parent;
  private final Class<U> currentType;
  private final PropertyUtils propertyUtils;
  private final List<String> path;
  private final List<R> roles;
  private final String pendingPrefix;

  AnswerBuilder(ChallengeQuestionField.QuestionBuilder<T, R> parent,
                Class<U> currentType,
                PropertyUtils propertyUtils,
                List<R> roles) {
    this(parent, currentType, propertyUtils, roles, Lists.newArrayList(), "");
  }

  private AnswerBuilder(ChallengeQuestionField.QuestionBuilder<T, R> parent,
                        Class<U> currentType,
                        PropertyUtils propertyUtils,
                        List<R> roles,
                        List<String> path,
                        String pendingPrefix) {
    this.parent = parent;
    this.currentType = currentType;
    this.propertyUtils = propertyUtils;
    this.roles = roles;
    this.path = path;
    this.pendingPrefix = pendingPrefix;
  }

  public <V> AnswerBuilder<V, T, R> complex(TypedPropertyGetter<U, V> getter) {
    JsonUnwrapped unwrapped = propertyUtils.getAnnotationOfProperty(
        currentType, getter, JsonUnwrapped.class);
    Class<V> nextType = propertyUtils.getPropertyType(currentType, getter);
    if (unwrapped != null) {
      String newPrefix = pendingPrefix.isEmpty()
          ? unwrapped.prefix()
          : pendingPrefix + StringUtils.capitalize(unwrapped.prefix());
      return new AnswerBuilder<>(parent, nextType, propertyUtils, roles, path, newPrefix);
    }
    String name = applyPrefix(propertyUtils.getPropertyName(currentType, getter));
    List<String> nextPath = Lists.newArrayList(path);
    nextPath.add(name);
    return new AnswerBuilder<>(parent, nextType, propertyUtils, roles, nextPath, "");
  }

  public ChallengeQuestionField.QuestionBuilder<T, R> field(TypedPropertyGetter<U, ?> getter) {
    String name = applyPrefix(propertyUtils.getPropertyName(currentType, getter));
    path.add(name);
    return finish();
  }

  public ChallengeQuestionField.QuestionBuilder<T, R> field(String literal) {
    path.add(literal);
    return finish();
  }

  private String applyPrefix(String name) {
    return pendingPrefix.isEmpty() ? name : pendingPrefix + StringUtils.capitalize(name);
  }

  private ChallengeQuestionField.QuestionBuilder<T, R> finish() {
    parent.addAnswer(path, roles);
    return parent;
  }
}
