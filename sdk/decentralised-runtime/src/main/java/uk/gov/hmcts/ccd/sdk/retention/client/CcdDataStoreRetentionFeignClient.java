package uk.gov.hmcts.ccd.sdk.retention.client;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
    name = "ccd-data-store-retention-client",
    url = "${ccd.data-store.api.url:${core_case_data.api.url:}}",
    configuration = CcdDataStoreRetentionFeignConfig.class
)
public interface CcdDataStoreRetentionFeignClient {

  @PostMapping(
      value = "/internal/cases/existence",
      consumes = APPLICATION_JSON_VALUE,
      produces = APPLICATION_JSON_VALUE
  )
  CcdCaseDataExistenceResponse caseDataExists(@RequestBody CcdCaseDataExistenceRequest request);
}
