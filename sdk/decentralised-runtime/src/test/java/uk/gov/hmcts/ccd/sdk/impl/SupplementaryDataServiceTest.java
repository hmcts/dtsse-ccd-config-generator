package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

class SupplementaryDataServiceTest {

  private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final SupplementaryDataService service = new SupplementaryDataService(jdbc, mapper);

  @Test
  void updateSupplementaryDataRejectsNullRequest() {
    assertThatThrownBy(() -> service.updateSupplementaryData(123L, null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(error -> {
          var statusException = (ResponseStatusException) error;
          assertThat(statusException.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
          assertThat(statusException.getReason()).isEqualTo("supplementary_data_updates must not be null or empty");
        });

    verifyNoInteractions(jdbc);
  }

  @Test
  void updateSupplementaryDataRejectsNullRequestData() {
    var request = new SupplementaryDataUpdateRequest();
    request.setRequestData(null);

    assertThatThrownBy(() -> service.updateSupplementaryData(123L, request))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(error -> {
          var statusException = (ResponseStatusException) error;
          assertThat(statusException.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
          assertThat(statusException.getReason()).isEqualTo("supplementary_data_updates must not be null or empty");
        });

    verifyNoInteractions(jdbc);
  }

  @Test
  void updateSupplementaryDataRejectsEmptyRequestData() {
    var request = new SupplementaryDataUpdateRequest();
    request.setRequestData(Map.of());

    assertThatThrownBy(() -> service.updateSupplementaryData(123L, request))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(error -> {
          var statusException = (ResponseStatusException) error;
          assertThat(statusException.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
          assertThat(statusException.getReason()).isEqualTo("supplementary_data_updates must not be null or empty");
        });

    verifyNoInteractions(jdbc);
  }

  @Test
  void updateSupplementaryDataRejectsEmptyOperationSet() {
    var request = new SupplementaryDataUpdateRequest();
    request.setRequestData(Map.of("$set", Map.of()));

    assertThatThrownBy(() -> service.updateSupplementaryData(123L, request))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(error -> {
          var statusException = (ResponseStatusException) error;
          assertThat(statusException.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
          assertThat(statusException.getReason())
              .isEqualTo("supplementary_data_updates operation values must not be null or empty");
        });

    verifyNoInteractions(jdbc);
  }

  @Test
  void updateSupplementaryDataUpdatesAndReturnsPayload() {
    var request = new SupplementaryDataUpdateRequest();
    request.setRequestData(Map.of("$set", Map.of("hmcts.foo", 1)));
    when(jdbc.queryForObject(any(String.class), any(Map.class), eq(String.class)))
        .thenReturn("{\"hmcts\":{\"foo\":1}}");

    var response = service.updateSupplementaryData(123L, request);

    assertThat(response.getSupplementaryData().path("hmcts").path("foo").asInt()).isEqualTo(1);
    verify(jdbc).queryForObject(any(String.class), any(Map.class), eq(String.class));
  }
}
