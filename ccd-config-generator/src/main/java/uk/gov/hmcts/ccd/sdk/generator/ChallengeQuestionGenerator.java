package uk.gov.hmcts.ccd.sdk.generator;

import static java.util.stream.Collectors.toList;
import static uk.gov.hmcts.ccd.sdk.generator.JsonUtils.mergeInto;

import com.google.common.collect.Maps;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ChallengeQuestion;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

@Component
public class ChallengeQuestionGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  @SneakyThrows
  public void write(final File outputfolder, ResolvedCCDConfig<T, S, R> config) {

    List<ChallengeQuestion> challengeQuestions = config.getChallengeQuestions();

    if (null != challengeQuestions && !challengeQuestions.isEmpty()) {
      final List<Map<String, Object>> questions = config.getChallengeQuestions().stream()
          .map(o -> toJson(config.getCaseType(), o))
          .collect(toList());
      Path output = Paths.get(outputfolder.getPath(), "ChallengeQuestion.json");
      mergeInto(output, questions, new JsonUtils.AddMissing(), "QuestionId");
    }
  }


  @SneakyThrows
  private static Map<String, Object> toJson(String caseType, ChallengeQuestion question) {
    Map<String, Object> field = Maps.newHashMap();
    field.put("LiveFrom", "01/04/2024");
    field.put("CaseTypeID", caseType);
    field.put("ID", "NoCChallenge");
    field.put("QuestionText", question.getQuestionText());
    field.put("AnswerFieldType", "Text");
    field.put("Answer", question.getAnswer());
    field.put("QuestionId", question.getQuestionId());

    return field;
  }

}
