package uk.gov.hmcts.ccd.sdk.generator;


import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ChallengeQuestion;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ChallengeQuestionGeneratorTest {

  @Mock
  ResolvedCCDConfig<String, String, HasRole> mockConfig;

  @InjectMocks
  ChallengeQuestionGenerator<String, String, HasRole> generator;

  private static final String OUTPUT_DIRECTORY = "test-output";

  @Before
  public void initMocks() {
    MockitoAnnotations.openMocks(this);
    // Create the output directory if it doesn't exist
    File outputDir = new File(OUTPUT_DIRECTORY);
    outputDir.mkdir();
  }

  @AfterEach
  void tearDown() {
    // Clean up the output directory after each test
    File outputDir = new File(OUTPUT_DIRECTORY);
    File[] files = outputDir.listFiles();
    if (files != null) {
      for (File file : files) {
        file.delete();
      }
    }
    outputDir.delete();
  }

  @Test
  public void write_SuccessfullyWritesToFile() throws Exception {
    // Mock data
    List<ChallengeQuestion> mockQuestions = new ArrayList<>();
    mockQuestions.add(ChallengeQuestion.builder()
      .questionText("Question 1").answer("Answer 1").questionId("question1Id").build());
    mockQuestions.add(ChallengeQuestion.builder()
      .questionText("Question 2").answer("Answer 2").questionId("question2Id").build());

    // Mock config
    when(mockConfig.getChallengeQuestions()).thenReturn(mockQuestions);
    when(mockConfig.getCategories()).thenReturn(new ArrayList<>()); // Assuming this is mocked for simplicity
    when(mockConfig.getCaseType()).thenReturn("derived");
    // Call write method
    File outputFolder = new File(OUTPUT_DIRECTORY);
    generator.write(outputFolder, mockConfig);

    // Verify output file contents
    File outputFile = new File(OUTPUT_DIRECTORY, "ChallengeQuestion.json");
    File testFile = new File("src/test/resources/derived/ChallengeQuestion.json");
    String result = Files.readString(outputFile.toPath());
    String expected = Files.readString(testFile.toPath());
    assertEquals(expected, result);
  }
}

