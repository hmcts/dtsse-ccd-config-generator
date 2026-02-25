package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CaseDataRepositoryTest {

  private final CaseDataRepository repository = spy(new CaseDataRepository(
      mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class),
      new ObjectMapper()
  ));

  @Test
  void getCaseWhenMissingReturnsNotFound() {
    long missingCaseRef = 1234567890123456L;
    when(repository.getCases(List.of(missingCaseRef))).thenReturn(List.of());

    assertThatThrownBy(() -> repository.getCase(missingCaseRef))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> {
          ResponseStatusException rse = (ResponseStatusException) ex;
          org.assertj.core.api.Assertions.assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
          org.assertj.core.api.Assertions.assertThat(rse.getReason()).isEqualTo("Case not found");
        });

    verify(repository).getCases(List.of(missingCaseRef));
  }
}
