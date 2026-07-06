package uk.gov.hmcts.ccd.sdk.retention.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(
    name = "ccd-data-store-retention-client",
    url = "${ccd.data-store.api.url:${core_case_data.api.url:}}",
    configuration = CcdDataStoreRetentionFeignConfig.class
)
public interface CcdDataStoreRetentionFeignClient {

  @GetMapping("/caseworkers/{uid}/jurisdictions/{jurisdiction}/case-types/{caseTypeId}/cases/{caseReference}")
  JsonNode retrieveCase(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                        @PathVariable("uid") String uid,
                        @PathVariable("jurisdiction") String jurisdiction,
                        @PathVariable("caseTypeId") String caseTypeId,
                        @PathVariable("caseReference") String caseReference);
}
