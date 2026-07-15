package uk.gov.hmcts.ccd.sdk.converter.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExclusionFilterTest {

  @Test
  void notExcludedWhenNoPatternsGiven() {
    assertThat(ExclusionFilter.isExcluded("CaseEvent-nonprod.json", List.of())).isFalse();
  }

  @Test
  void excludesFlatFileMatchingPattern() {
    assertThat(ExclusionFilter.isExcluded("CaseEvent-nonprod.json", List.of("*-nonprod.json")))
        .isTrue();
  }

  @Test
  void doesNotExcludeFlatFileThatDoesNotMatch() {
    assertThat(ExclusionFilter.isExcluded("CaseEvent.json", List.of("*-nonprod.json"))).isFalse();
  }

  @Test
  void excludesNestedFileWhenAnySegmentMatches() {
    assertThat(ExclusionFilter.isExcluded(
        "CaseEvent/someHandler/hook-nonprod.json", List.of("*-nonprod.json")))
        .isTrue();
  }

  @Test
  void excludesNestedFileWhenDirectorySegmentMatches() {
    assertThat(ExclusionFilter.isExcluded(
        "UserProfile/rows.json", List.of("UserProfile")))
        .isTrue();
  }

  @Test
  void exactNamePatternExcludesMatchingFlatFile() {
    assertThat(ExclusionFilter.isExcluded("UserProfile.json", List.of("UserProfile.json")))
        .isTrue();
  }

  @Test
  void exactNamePatternDoesNotExcludeNonMatchingFile() {
    assertThat(ExclusionFilter.isExcluded("CaseField.json", List.of("UserProfile.json")))
        .isFalse();
  }

  @Test
  void multiplePatternsSatisfiedByAny() {
    assertThat(ExclusionFilter.isExcluded("CaseEvent.json",
        List.of("*-nonprod.json", "CaseEvent.json")))
        .isTrue();
  }
}
