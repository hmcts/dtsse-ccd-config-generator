package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import lombok.SneakyThrows;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import uk.gov.hmcts.reform.ccd.client.model.AboutToStartOrSubmitCallbackResponse;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.fpl.model.CaseData;

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
  public void testAboutToSubmit() {
    this.makeRequest("about-to-submit", "addFamilyManCaseNumber")
        .andExpect(status().isOk());
  }

  @SneakyThrows
  @Test
  public void testSubmitted() {
    this.makeRequest("submitted", "addFamilyManCaseNumber")
        .andExpect(status().isOk());
  }

  @SneakyThrows
  @Test
  public void testNoCallbackReturns404() {
    this.makeRequest("submitted", "does-not-exist")
        .andExpect(status().is4xxClientError());
  }

  @SneakyThrows
  @Test
  public void testMidEventCallback() {
    MvcResult result =
        this.makeRequest("mid-event?page=1", "addFamilyManCaseNumber")
            .andExpect(status().isOk())
            .andReturn();
    CaseData data = getResponseData(result);
    assertThat(data.getFamilyManCaseNumber()).isEqualTo("PLACEHOLDER");
  }

  @SneakyThrows
  @Test
  public void testMidEventCallbackUndefined() {
    this.makeRequest("mid-event?page=DoesntExist", "addFamilyManCaseNumber")
        .andExpect(status().is4xxClientError());
  }

  @SneakyThrows
  ResultActions makeRequest(String callback, String eventId) {
    return this.mockMvc.perform(post("/callbacks/" + callback)
        .contentType(MediaType.APPLICATION_JSON)
        .content(new ObjectMapper().writeValueAsString(buildRequest(eventId))));
  }

  @SneakyThrows
  CaseData getResponseData(MvcResult result) {
    ObjectMapper mapper = new ObjectMapper();
    AboutToStartOrSubmitCallbackResponse r =
        mapper.readValue(result.getResponse().getContentAsString(),
            AboutToStartOrSubmitCallbackResponse.class);

    String json = mapper.writeValueAsString(r.getData());
    return mapper.readValue(json, CaseData.class);
  }

  CallbackRequest buildRequest(String eventId) {
    return CallbackRequest.builder()
        .eventId(eventId)
        .caseDetails(CaseDetails.builder()
            .data(Maps.newHashMap())
            .build())
        .build();
  }
}
