package uk.gov.hmcts.ccd.sdk.jackson;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import org.junit.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.config.CcdJacksonConfiguration;
import uk.gov.hmcts.ccd.sdk.type.AddressGlobalUK;
import uk.gov.hmcts.ccd.sdk.type.AddressUK;
import uk.gov.hmcts.ccd.sdk.type.CaseLink;
import uk.gov.hmcts.ccd.sdk.type.CaseLocation;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.ccd.sdk.type.DynamicList;
import uk.gov.hmcts.ccd.sdk.type.DynamicMultiSelectList;
import uk.gov.hmcts.ccd.sdk.type.Flags;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.ccd.sdk.type.OrderSummary;
import uk.gov.hmcts.ccd.sdk.type.OrganisationPolicy;
import uk.gov.hmcts.ccd.sdk.type.ScannedDocument;
import uk.gov.hmcts.ccd.sdk.type.YesOrNo;

public class UnwrappedPrefixModuleTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String DOCUMENT = """
      {"document_url":"http://dm/documents/1","document_binary_url":"http://dm/documents/1/binary",
       "document_filename":"one.pdf"}""";
  private static final String ADDRESS = """
      {"AddressLine1":"1 Street","AddressLine2":"Flat 2","AddressLine3":"Block 3","PostTown":"London",
       "County":"Greater London","PostCode":"SW1A 1AA","Country":"United Kingdom"}""";
  private static final String POLICY = """
      {"Organisation":{"OrganisationID":"ORG1","OrganisationName":"Org One"},"OrgPolicyReference":"ref",
       "PrepopulateToUsersOrganisation":"No","OrgPolicyCaseAssignedRole":"[SOLICITOR]"}""";

  private static final String PARTY_FIELDS = """
      "name":"Party",
      "document":%1$s,
      "addressUk":%2$s,
      "addressGlobalUk":%2$s,
      "organisationPolicy":%3$s,
      "orderSummary":{"PaymentReference":"RC-1","PaymentTotal":"4600",
        "Fees":[{"id":"1","value":{"FeeAmount":"4600","FeeCode":"FEE0391","FeeDescription":"Fee","FeeVersion":"1"}}]},
      "dynamicList":{"value":{"code":"00000000-0000-0000-0000-000000000001","label":"One"},
        "list_items":[{"code":"00000000-0000-0000-0000-000000000001","label":"One"}],
        "valueCode":"00000000-0000-0000-0000-000000000001","valueLabel":"One"},
      "dynamicMultiSelectList":{"value":[{"code":"00000000-0000-0000-0000-000000000002","label":"Two"}],
        "list_items":[{"code":"00000000-0000-0000-0000-000000000002","label":"Two"}]},
      "caseLink":{"CaseReference":"1616591401473378","CaseType":"NFD","CreatedDateTime":"2026-01-02T03:04:05.678",
        "ReasonForLink":[{"id":"2","value":{"Reason":"CLRC001","OtherDescription":"Linked"}}]},
      "caseLocation":{"region":"1","baseLocation":"123456"},
      "flags":{"partyName":"Party","roleOnCase":"Applicant","details":[{"id":"3","value":{"name":"Other",
        "flagCode":"OT0001","status":"Active","hearingRelevant":"No","dateTimeCreated":"2026-01-02T03:04:05.000Z",
        "path":[{"id":"4","value":"Party"}]}}]},
      "scannedDocument":{"type":"form","subtype":"D8","url":%1$s,"controlNumber":"123","fileName":"scan.pdf",
        "scannedDate":"2026-01-02T03:04:05.000","deliveryDate":"2026-01-03T03:04:05.000",
        "exceptionRecordReference":"1111"},
      "documents":[{"id":"5","value":%1$s}],
      "notification":{"id":"n1","status":"delivered"},
      "confirmed":"Yes"
      """.formatted(DOCUMENT, ADDRESS, POLICY);

  @Test
  public void keepsNestedValuesBehindAPrefixedUnwrap() throws Exception {
    assertRoundTrips(withPrefix("party", party()), PrefixedHolder.class);
  }

  @Test
  public void keepsPropertiesUnprefixedWhenTheUnwrapHasNoPrefix() throws Exception {
    assertRoundTrips(party(), PlainHolder.class);
  }

  @Test
  public void leavesACreatorTypeThatIsItselfTheUnwrappedTargetAsJacksonReadsIt() throws Exception {
    ObjectNode json = withPrefix("policy", (ObjectNode) JSON.readTree(POLICY));

    JsonNode withModule = roundTrip(mapper(true), json, UnwrappedPolicyHolder.class);
    JsonNode withoutModule = roundTrip(mapper(false), json, UnwrappedPolicyHolder.class);

    assertThat(withModule).isEqualTo(withoutModule);
  }

  @Test
  public void registersTheModuleOnEveryObjectMapperBean() {
    new ApplicationContextRunner()
        .withUserConfiguration(CcdJacksonConfiguration.class)
        .withBean("serviceMapper", ObjectMapper.class, () -> JsonMapper.builder().build())
        .run(context -> assertThat(context.getBean(ObjectMapper.class).getRegisteredModuleIds())
            .contains(UnwrappedPrefixModule.class.getName()));
  }

  private static ObjectNode party() throws Exception {
    ObjectNode party = (ObjectNode) JSON.readTree("{" + PARTY_FIELDS + "}");
    party.put("Solicitorname", "Solicitor");
    party.set("SolicitororganisationPolicy", JSON.readTree(POLICY));
    party.set("Solicitoraddress", JSON.readTree(ADDRESS));
    return party;
  }

  private static ObjectNode withPrefix(String prefix, ObjectNode fields) {
    ObjectNode prefixed = JSON.createObjectNode();
    fields.fields().forEachRemaining(field -> prefixed.set(prefix + field.getKey(), field.getValue()));
    return prefixed;
  }

  private static void assertRoundTrips(JsonNode json, Class<?> type) throws Exception {
    assertThat(roundTrip(mapper(true), json, type)).isEqualTo(json);
  }

  private static JsonNode roundTrip(ObjectMapper mapper, JsonNode json, Class<?> type) throws Exception {
    return mapper.readTree(mapper.writeValueAsString(mapper.treeToValue(json, type)));
  }

  private static ObjectMapper mapper(boolean withModule) {
    JsonMapper.Builder builder = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .serializationInclusion(JsonInclude.Include.NON_NULL);
    if (withModule) {
      builder.addModule(new UnwrappedPrefixModule());
    }
    return builder.build();
  }

  public enum Role implements HasRole {
    SOLICITOR;

    @Override
    public String getRole() {
      return "[SOLICITOR]";
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRU";
    }

    @JsonValue
    public String toJson() {
      return getRole();
    }
  }

  public static class Notification {
    public String id;
    public String status;
  }

  public static class Solicitor {
    public String name;
    public OrganisationPolicy<Role> organisationPolicy;
    public AddressUK address;
  }

  public static class Party {
    public String name;
    public Document document;
    public AddressUK addressUk;
    public AddressGlobalUK addressGlobalUk;
    public OrganisationPolicy<Role> organisationPolicy;
    public OrderSummary orderSummary;
    public DynamicList dynamicList;
    public DynamicMultiSelectList dynamicMultiSelectList;
    public CaseLink caseLink;
    public CaseLocation caseLocation;
    public Flags flags;
    public ScannedDocument scannedDocument;
    public List<ListValue<Document>> documents;
    public Notification notification;
    public YesOrNo confirmed;
    @JsonUnwrapped(prefix = "Solicitor")
    public Solicitor solicitor = new Solicitor();
  }

  public static class PrefixedHolder {
    @JsonUnwrapped(prefix = "party")
    public Party party = new Party();
  }

  public static class PlainHolder {
    @JsonUnwrapped
    public Party party = new Party();
  }

  public static class UnwrappedPolicyHolder {
    @JsonUnwrapped(prefix = "policy")
    public OrganisationPolicy<Role> policy;
  }
}
