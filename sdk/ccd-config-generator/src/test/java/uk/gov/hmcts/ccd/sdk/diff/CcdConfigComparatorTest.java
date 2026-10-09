package uk.gov.hmcts.ccd.sdk.diff;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.FileUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.skyscreamer.jsonassert.JSONCompareMode;

public class CcdConfigComparatorTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void reportsADifferingNullValuedRowInsteadOfThrowingNpe() throws Exception {
    File expected = tmp.newFile("expected.json");
    File actual = tmp.newFile("actual.json");
    FileUtils.writeStringToFile(expected,
        "[{\"CaseFieldID\":\"r\",\"CaseTypeID\":\"X\",\"ResultsOrdering\":null}]",
        StandardCharsets.UTF_8);
    FileUtils.writeStringToFile(actual,
        "[{\"CaseFieldID\":\"r\",\"CaseTypeID\":\"X\",\"ResultsOrdering\":\"1:ASC\"}]",
        StandardCharsets.UTF_8);

    assertThatThrownBy(() -> CcdConfigComparator.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE))
        .isInstanceOf(RuntimeException.class)
        .isNotInstanceOf(NullPointerException.class)
        .hasMessageContaining("Compare failed for");
  }
}
