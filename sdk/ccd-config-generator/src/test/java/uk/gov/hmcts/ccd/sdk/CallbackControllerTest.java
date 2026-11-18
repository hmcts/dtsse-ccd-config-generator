package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import uk.gov.hmcts.reform.PostEventHookCaseType;
import uk.gov.hmcts.reform.fpl.BulkCaseConfig;
import uk.gov.hmcts.reform.fpl.model.CaseData;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.CCD_SOLICITOR;

@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
public class CallbackControllerTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNode NOC_FIXTURES = readNocFixtures();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ResolvedConfigRegistry registry;

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
    assertThat(response.getErrors()).isNull();
    assertThat(response.getWarnings()).isNull();
    assertThat(response.getState()).isNull();
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

  @Test
  public void aChangeOrganisationRequestRaisedByAServiceRoundTrips() {
    JsonNode stored = nocFixture("raisedByService");

    assertThat(roundTrip("changeOrganisationRequestField", stored)).isEqualTo(stored);
  }

  @Test
  public void aNoticeOfChangeRequestWrittenByAacRoundTrips() {
    JsonNode stored = nocFixture("requestedThroughAac");

    assertThat(roundTrip("changeOrganisationRequestField", stored)).isEqualTo(stored);
  }

  @Test
  public void aChangeOrganisationRequestClearedByAacRoundTripsWithoutItsNullMembers() {
    JsonNode stored = nocFixture("appliedByAac");
    ObjectNode expected = stored.deepCopy();
    expected.retain("OrganisationToAdd", "OrganisationToRemove");

    assertThat(roundTrip("changeOrganisationRequestField", stored)).isEqualTo(expected);
  }

  @Test
  public void anOrganisationPolicyKeepsLastNoCRequestedByAndThePreviousOrganisationsInOrder() {
    JsonNode stored = nocFixture("organisationPolicyAfterNoticesOfChange");

    assertThat(roundTrip("organisationPolicy", stored)).isEqualTo(stored);
  }

  @Test
  public void anEmptyCaseRoleIdRoundTrips() {
    JsonNode stored = nocFixture("caseRoleIdEmpty");

    assertThat(roundTrip("changeOrganisationRequestField", stored)).isEqualTo(stored);
  }

  @Test
  public void aCaseRoleIdWithoutListItemsRoundTrips() {
    JsonNode stored = nocFixture("caseRoleIdWithoutListItems");

    assertThat(roundTrip("changeOrganisationRequestField", stored)).isEqualTo(stored);
  }

  @SneakyThrows
  private JsonNode roundTrip(String field, JsonNode value) {
    Map<String, Object> data = Maps.newHashMap();
    data.put(field, MAPPER.convertValue(value, Object.class));

    MvcResult result = this.makeRequest("about-to-start", "CARE_SUPERVISION_EPO", "noticeOfChangeApplied", data)
        .andExpect(status().isOk())
        .andReturn();

    return MAPPER.readTree(result.getResponse().getContentAsString()).path("data").path(field);
  }

  private JsonNode nocFixture(String name) {
    return NOC_FIXTURES.get(name).deepCopy();
  }

  @SneakyThrows
  private static JsonNode readNocFixtures() {
    return MAPPER.readTree(Resources.getResource("request.casedata/notice-of-change-round-trip.json"));
  }

  @SneakyThrows
  @Test
  public void testSearchPartySerialization() {
    Map<String, Object> data = Maps.newHashMap();
    data.put("caseLocalAuthority", "LA1");
    Map<String, Object> searchParty = Maps.newHashMap();
    searchParty.put("CollectionFieldName", "respondents1");
    searchParty.put("Name", "Alex Bonfire");
    searchParty.put("EmailAddress", "alex@example.com");
    searchParty.put("AddressLine1", "10 Test Street");
    searchParty.put("PostCode", "SW1A 2AA");
    searchParty.put("DateOfBirth", "2000-01-01");
    searchParty.put("DateOfDeath", "2020-12-31");
    data.put("SearchParty", searchParty);

    MvcResult result = this.makeRequest("about-to-start", "CARE_SUPERVISION_EPO", "addFamilyManCaseNumber", data)
        .andExpect(status().isOk())
        .andReturn();

    AboutToStartOrSubmitCallbackResponse response = getCallbackResponse(result);
    @SuppressWarnings("unchecked")
    Map<String, Object> responseSearchParty = (Map<String, Object>) response.getData().get("SearchParty");
    assertThat(responseSearchParty.get("DateOfBirth")).isEqualTo("2000-01-01");
    assertThat(responseSearchParty.get("DateOfDeath")).isEqualTo("2020-12-31");

    CaseData caseData = getResponseData(result, CaseData.class);
    uk.gov.hmcts.ccd.sdk.type.SearchParty party = caseData.getSearchParty();
    assertThat(party).isNotNull();
    assertThat(party.getCollectionFieldName()).isEqualTo("respondents1");
    assertThat(party.getPostcode()).isEqualTo("SW1A 2AA");
    assertThat(party.getDateOfBirth()).isEqualTo(LocalDate.of(2000, 1, 1));
    assertThat(party.getDateOfDeath()).isEqualTo(LocalDate.of(2020, 12, 31));
  }

  @SneakyThrows
  @Test
  public void postEventHooksRunInRegistrationOrderOnTheHandlersResponse() {
    MvcResult result = this.makeRequest("about-to-submit", "PostEventHook", "edit")
        .andExpect(status().isOk())
        .andReturn();

    AboutToStartOrSubmitCallbackResponse response = getCallbackResponse(result);
    assertThat(response.getData())
        .containsEntry("note", "submitted")
        .containsEntry("hookTrail", "first:edit:submitted:Submitted|second");
    assertThat(response.getWarnings()).containsExactly("first", "second");
    assertThat(response.getState()).isEqualTo("Submitted");
  }

  @SneakyThrows
  @Test
  public void postEventHooksRunWhenTheHandlerReturnsErrors() {
    MvcResult result = this.makeRequest("about-to-submit", "PostEventHook", "handlerErrors")
        .andExpect(status().isOk())
        .andReturn();

    AboutToStartOrSubmitCallbackResponse response = getCallbackResponse(result);
    assertThat(response.getErrors()).containsExactly("Refused by the handler");
    assertThat(response.getData()).containsEntry("hookTrail", "first:handlerErrors:null:null|second");
  }

  @Test
  public void anExceptionFromAPostEventHookFailsTheCallback() {
    assertThatThrownBy(() -> this.makeRequest("about-to-submit", "PostEventHook", "refuse"))
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage(PostEventHookCaseType.HOOK_REJECTION);
  }

  @SneakyThrows
  @Test
  public void postEventHooksDoNotRunForAboutToStart() {
    MvcResult result = this.makeRequest("about-to-start", "PostEventHook", "edit")
        .andExpect(status().isOk())
        .andReturn();

    AboutToStartOrSubmitCallbackResponse response = getCallbackResponse(result);
    assertThat(response.getData()).containsEntry("note", "started");
    assertThat(response.getData().get("hookTrail")).isNull();
    assertThat(response.getWarnings()).isNull();
  }

  @SneakyThrows
  @Test
  public void postEventHooksDoNotRunForMidEvent() {
    MvcResult result = this.makeRequest("mid-event?page=1", "PostEventHook", "edit")
        .andExpect(status().isOk())
        .andReturn();

    AboutToStartOrSubmitCallbackResponse response = getCallbackResponse(result);
    assertThat(response.getData()).containsEntry("note", "mid-event");
    assertThat(response.getData().get("hookTrail")).isNull();
    assertThat(response.getWarnings()).isNull();
  }

  @SneakyThrows
  @Test
  public void postEventHooksReceiveAnEmptyResponseWhenTheHandlerReturnsNone() {
    MvcResult result = this.makeRequest("about-to-submit", "PostEventHook", "handlerReturnsNull")
        .andExpect(status().isOk())
        .andReturn();

    AboutToStartOrSubmitCallbackResponse response = getCallbackResponse(result);
    assertThat(response.getData()).containsEntry("hookTrail", "first:handlerReturnsNull:null:null|second");
    assertThat(response.getErrors()).isNull();
    assertThat(response.getState()).isNull();
  }

  @Test
  public void aCaseTypeWithoutPostEventHooksPassesOnANullResponse() {
    assertThat(registry.applyPostEventHooks("CARE_SUPERVISION_EPO", "addFamilyManCaseNumber", null, null, null))
        .isNull();
  }

  @Test
  public void aPostEventHookReturningNoResponseFailsTheCallback() {
    assertThatThrownBy(() -> this.makeRequest("about-to-submit", "PostEventHook", "hookReturnsNull"))
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("A post-event hook of case type PostEventHook returned no response for event "
            + "hookReturnsNull");
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
    mapper.findAndRegisterModules();
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
