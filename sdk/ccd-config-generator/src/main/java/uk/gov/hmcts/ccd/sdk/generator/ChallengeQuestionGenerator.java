package uk.gov.hmcts.ccd.sdk.generator;

import static java.util.stream.Collectors.joining;
import static uk.gov.hmcts.ccd.sdk.generator.JsonUtils.mergeInto;

import com.google.common.collect.Lists;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ChallengeQuestion;
import uk.gov.hmcts.ccd.sdk.api.ChallengeQuestionField;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.NoticeOfChange;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
public class ChallengeQuestionGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  @SneakyThrows
  public void write(final File outputFolder, ResolvedCCDConfig<T, S, R> config) {
    NoticeOfChange<T, R> noc = config.getNoticeOfChange();
    if (noc == null || noc.getChallenges().isEmpty()) {
      return;
    }

    final List<Map<String, Object>> result = Lists.newArrayList();
    for (ChallengeQuestion<T, R> challenge : noc.getChallenges()) {
      for (ChallengeQuestionField<R> question : challenge.getQuestions()) {
        result.add(toJson(config.getCaseType(), challenge.getId(), question));
      }
    }

    final Path path = Paths.get(outputFolder.getPath(), "ChallengeQuestion.json");
    mergeInto(path, result, new AddMissing(), "CaseTypeID", "ID", "QuestionId");
  }

  /**
   * Default behaviour: wrap unbracketed roles so existing services that pass IDAM /
   * access-profile names keep emitting {@code [role]} Answers that definition-store
   * already accepts. Opt-in {@code answerAsDeclared} skips wrapping so group roles
   * like {@code defendant-solicitor} can be emitted once definition-store allows them.
   */
  private static String formatRole(String role, boolean useRoleAsDeclared) {
    if (useRoleAsDeclared) {
      return role;
    }
    return role.startsWith("[") && role.endsWith("]") ? role : "[" + role + "]";
  }

  private static <R extends HasRole> Map<String, Object> toJson(
      String caseType, String id, ChallengeQuestionField<R> question) {
    Map<String, Object> row = JsonUtils.caseRow(caseType);
    row.put("ID", id);
    row.put("QuestionId", question.getQuestionId());
    row.put("QuestionText", question.getQuestionText());
    row.put("DisplayOrder", question.getDisplayOrder());
    row.put("AnswerFieldType", question.getAnswerFieldType());
    row.put("Answer", question.getAnswers().stream()
        .flatMap(a -> a.getRoles().stream()
            .map(role -> "${" + String.join(".", a.getPathSegments()) + "}:"
                + formatRole(role.getRole(), a.isUseRoleAsDeclared())))
        .collect(joining(",")));
    return row;
  }
}
