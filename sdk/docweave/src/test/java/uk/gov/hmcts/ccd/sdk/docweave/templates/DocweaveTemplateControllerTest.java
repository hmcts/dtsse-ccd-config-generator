package uk.gov.hmcts.ccd.sdk.docweave.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.authorisation.validators.AuthTokenValidator;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

class DocweaveTemplateControllerTest {

  private static final UUID USER_ID = UUID.randomUUID();

  @Test
  void authenticatesUsersDirectlyThroughTheIdamClient() {
    var templates = mock(DocweaveTemplateRepository.class);
    var s2s = mock(AuthTokenValidator.class);
    var idam = new StubIdamClient();
    var properties = new DocweaveTemplatesAutoConfiguration.Properties();
    properties.setAllowedServices(List.of("pcs_frontend"));

    when(s2s.getServiceName("Bearer service-token")).thenReturn("pcs_frontend");
    when(templates.search(USER_ID, "", false)).thenReturn(List.of());

    var controller = new DocweaveTemplateController(templates, idam, s2s, properties);

    assertThat(controller.search("bearer user-token", "service-token", "", "all").getStatusCode().is2xxSuccessful())
        .isTrue();
    assertThat(idam.lastToken).isEqualTo("Bearer user-token");
  }

  private static class StubIdamClient extends IdamClient {
    private String lastToken;

    StubIdamClient() {
      super(null, null);
    }

    @Override
    public UserInfo getUserInfo(String authorisation) {
      lastToken = authorisation;
      return new UserInfo("user@example.com", USER_ID.toString(), "Name", "Given", "Family", List.of("caseworker"));
    }
  }
}
