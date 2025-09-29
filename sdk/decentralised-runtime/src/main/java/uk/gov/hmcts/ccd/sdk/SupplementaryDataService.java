package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedUpdateSupplementaryDataResponse;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupplementaryDataService {

  private final NamedParameterJdbcTemplate ndb;
  private final ObjectMapper defaultMapper;

  @SneakyThrows
  @Transactional
  public DecentralisedUpdateSupplementaryDataResponse updateSupplementaryData(
      long caseRef,
      SupplementaryDataUpdateRequest request
  ) {
    final AtomicReference<String> result = new AtomicReference<>();
    request.getRequestData()
        .forEach((operationType, operationSet) -> {
          operationSet.forEach((key, value) -> {
            var path = key.split("\\.");
            log.info(
                "Updating supplementary data for caseRef: {}, operationType: {}, path: {}, value: {}",
                caseRef,
                operationType,
                path,
                value
            );
            var updatedValue = ndb.queryForObject(
                """
                    UPDATE ccd.case_data SET supplementary_data = jsonb_set_lax(
                        -- Create the top level entry as a map if it doesn't exist.
                        jsonb_set(
                            supplementary_data,
                            (:path)[ 1 : 1 ],
                            coalesce(supplementary_data #> (:path)[1 : 1], '{}')::jsonb
                        ),
                        :path,
                        (
                            case
                                when :op = '$inc' then (
                                    coalesce((supplementary_data #> :path)::integer, 0)
                                    + (:value)::integer
                                )::text::jsonb
                                when :op = '$set' then to_jsonb(:value)
                                else null -- any other operation will raise an exception
                            end
                        ),
                        true,
                        'raise_exception' -- on setting a null value
                    )
                    where reference = :reference
                    returning
                        supplementary_data::text
                        as supplementary_data
                """,
                Map.of(
                    "path", path,
                    "value", value,
                    "reference", caseRef,
                    "op", operationType
                ),
                String.class
            );
            result.set(updatedValue);
          });
        });

    var response = new DecentralisedUpdateSupplementaryDataResponse();
    response.setSupplementaryData(defaultMapper.readTree(result.get()));
    return response;
  }
}
