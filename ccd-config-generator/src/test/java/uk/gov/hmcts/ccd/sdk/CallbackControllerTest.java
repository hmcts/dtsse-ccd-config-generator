package uk.gov.hmcts.ccd.sdk;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import lombok.SneakyThrows;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;

@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
public class CallbackControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @SneakyThrows
  @Test
  public void testAboutToStart() {
    this.mockMvc.perform(post("/callbacks/about-to-start")
        .contentType(MediaType.APPLICATION_JSON)
        .content(new ObjectMapper().writeValueAsString(buildRequest())))
        .andExpect(status().isOk());
  }

  @SneakyThrows
  @Test
  public void testAboutToSubmit() {
    this.mockMvc.perform(post("/callbacks/about-to-submit")
        .contentType(MediaType.APPLICATION_JSON)
        .content(new ObjectMapper().writeValueAsString(buildRequest())))
        .andExpect(status().isOk());
  }

  @SneakyThrows
  @Test
  public void testSubmitted() {
    this.mockMvc.perform(post("/callbacks/submitted")
        .contentType(MediaType.APPLICATION_JSON)
        .content(new ObjectMapper().writeValueAsString(buildRequest())))
        .andExpect(status().isOk());
  }

  @SneakyThrows
  @Test
  public void testNoCallbackReturns404() {
    CallbackRequest req = buildRequest();
    req.setEventId("does-not-exist");
    this.mockMvc.perform(post("/callbacks/submitted")
        .contentType(MediaType.APPLICATION_JSON)
        .content(new ObjectMapper().writeValueAsString(req)))
        .andExpect(status().is4xxClientError());
  }

  CallbackRequest buildRequest() {
    return CallbackRequest.builder()
        .eventId("addFamilyManCaseNumber")
        .caseDetails(CaseDetails.builder()
            .data(Maps.newHashMap())
            .build())
        .build();
  }
}
