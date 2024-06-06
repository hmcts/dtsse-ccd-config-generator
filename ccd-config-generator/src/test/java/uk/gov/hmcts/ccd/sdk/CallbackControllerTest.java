package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;
import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import uk.gov.hmcts.ccd.sdk.model.AboutToStartOrSubmitCallbackResponse;
import uk.gov.hmcts.ccd.sdk.type.PreviousOrganisationCollectionItem;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.fpl.BulkCaseConfig;
import uk.gov.hmcts.reform.fpl.model.CaseData;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.CCD_SOLICITOR;

@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
public class CallbackControllerTest {
  @Autowired
  private MockMvc mockMvc;

  @SneakyThrows
  @Test
  public void testAboutToStart() {
    this.makeRequest("about-to-start", "addFamilyManCaseNumber")
        .andExpect(status().isOk());
  }

  @SneakyThrows
  @Test
  public void testAboutToSubmitWithMigration() {
    Map<String, Object> data = Maps.newHashMap();
    data.put("orderAppliesToAllChildren", "test");

    this.makeRequest("about-to-submit", "CARE_SUPERVISION_EPO", "addFamilyManCaseNumber", data)
      .andExpect(status().isOk());
  }

  @SneakyThrows
  @Test
  public void testAboutToSubmitWithDataAndSecurityClassification() {
    Map<String, Object> data = Maps.newHashMap();

    MvcResult result = this.makeRequest("about-to-submit", "CARE_SUPERVISION_EPO", "setDataAndSecurityClassification", data)
      .andExpect(status().isOk())
      .andReturn();
    AboutToStartOrSubmitCallbackResponse response = getCallbackResponse(result);
    assertThat(response.getDataClassification()).containsExactly(Map.entry("field1", "PUBLIC"));
    assertThat(response.getSecurityClassification()).isEqualTo("PRIVATE");
  }

  @SneakyThrows
  @Test
  public void testSubmitted() {
    this.makeRequest("submitted", "addFamilyManCaseNumber")
        .andExpect(status().isOk());
  }

  @SneakyThrows
  @Test
  public void testUnknownEventReturns404() {
    this.makeRequest("submitted", "does-not-exist")
        .andExpect(status().is(404));
  }

  @SneakyThrows
  @Test
  public void testMissingCallbackReturns404() {
    this.makeRequest("submitted", "addNotes")
        .andExpect(status().is(404));
  }

  @SneakyThrows
  @Test
  public void testMidEventCallback() {
    MvcResult result =
        this.makeRequest("mid-event?page=1", "addFamilyManCaseNumber")
            .andExpect(status().isOk())
            .andReturn();
    CaseData data = getResponseData(result, CaseData.class);
    assertThat(data.getFamilyManCaseNumber()).isEqualTo("PLACEHOLDER");
  }

  @SneakyThrows
  @Test
  public void testMidEventCallbackUndefined() {
    this.makeRequest("mid-event?page=DoesntExist", "addFamilyManCaseNumber")
        .andExpect(status().is4xxClientError());
  }

  @SneakyThrows
  @Test
  public void testEventOnDifferentCaseType() {
    MvcResult result =
        this.makeRequest("about-to-start", "bulk", "addFamilyManCaseNumber")
            .andExpect(status().isOk())
            .andReturn();
    BulkCaseConfig.BulkCase data = getResponseData(result, BulkCaseConfig.BulkCase.class);
    assertThat(data.getFamilyManCaseNumber()).isEqualTo("bulk-about-to-start");
  }

  @SneakyThrows
  @Test
  public void testNoticeOfChangeAboutToStart() {
    Map<String, Object> data = caseData();

    MvcResult result = this.makeRequest("about-to-start", "CARE_SUPERVISION_EPO", "noticeOfChangeApplied", data)
            .andExpect(status().isOk())
            .andReturn();

    CaseData caseData = getResponseData(result, CaseData.class);

    PreviousOrganisationCollectionItem previousOrganisations = caseData.getOrganisationPolicy().getPreviousOrganisations().iterator().next();

    assertThat(caseData.getChangeOrganisationRequestField().getCaseRoleId().getRole()).isEqualTo("[APPONESOLICITOR]");
    assertThat(caseData.getOrganisationPolicy().getOrgPolicyCaseAssignedRole()).isEqualTo(CCD_SOLICITOR);
    assertThat(previousOrganisations).isInstanceOf(PreviousOrganisationCollectionItem.class);
    assertThat(previousOrganisations.getValue().getFromTimeStamp()).isNotNull();
    assertThat(previousOrganisations.getValue().getToTimeStamp()).isNotNull();

    String responseString = FileUtils.readFileToString(new File
                    (Resources.getResource("expected/response-notice-of-change-applied.json").getPath()),
            StandardCharsets.UTF_8);
    JSONAssert.assertEquals(responseString, result.getResponse().getContentAsString() , false);
  }

  @SneakyThrows
  ResultActions makeRequest(String callback, String eventId) {
    return makeRequest(callback, "CARE_SUPERVISION_EPO", eventId);
  }

  @SneakyThrows
  ResultActions makeRequest(String callback, String caseType, String eventId) {
    return makeRequest(callback, caseType, eventId, Maps.newHashMap());
  }

  @SneakyThrows
  ResultActions makeRequest(String callback, String caseType, String eventId, Map<String, Object> data) {
    return this.mockMvc.perform(post("/callbacks/" + callback)
        .contentType(MediaType.APPLICATION_JSON)
        .content(new ObjectMapper().writeValueAsString(buildRequest(caseType, eventId, data))));
  }

  @SneakyThrows
  <T> T getResponseData(MvcResult result, Class<T> c) {
    AboutToStartOrSubmitCallbackResponse r = getCallbackResponse(result);
    ObjectMapper mapper = new ObjectMapper();
    String json = mapper.writeValueAsString(r.getData());
    return mapper.readValue(json, c);
  }

  @SneakyThrows
  AboutToStartOrSubmitCallbackResponse getCallbackResponse(MvcResult result) {
    ObjectMapper mapper = new ObjectMapper();
    return
      mapper.readValue(result.getResponse().getContentAsString(),
        AboutToStartOrSubmitCallbackResponse.class);
  }

  CallbackRequest buildRequest(String caseType, String eventId, Map<String, Object> data) {
    return CallbackRequest.builder()
        .eventId(eventId)
        .caseDetails(CaseDetails.builder()
            .data(data)
            .caseTypeId(caseType)
            .build())
        .build();
  }

  @SneakyThrows
  private Map<String, Object> caseData() {
    return new ObjectMapper().readValue(
            FileUtils.readFileToString(new File(
                            Resources.getResource("request.casedata/ccd-callback-casedata-notice-of-change-applied.json").getPath()),
                    StandardCharsets.UTF_8),
            new TypeReference<>() {
            });
  }
}
