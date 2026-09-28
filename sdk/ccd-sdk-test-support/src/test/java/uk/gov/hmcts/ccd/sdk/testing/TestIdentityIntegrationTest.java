package uk.gov.hmcts.ccd.sdk.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import feign.FeignException;
import feign.RetryableException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import uk.gov.hmcts.reform.authorisation.ServiceAuthorisationApi;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGeneratorFactory;

/** An application's own Feign clients to the platform's identity services, answered by test support. */
@SpringBootTest(classes = TestIdentityIntegrationTest.FeignApplication.class,
    properties = "idam.s2s-auth.url=http://s2s.invalid")
class TestIdentityIntegrationTest {

  @Autowired
  private TestActors actors;
  @Autowired
  private Idam idam;
  @Autowired
  private RoleAssignment roleAssignment;
  @Autowired
  private ServiceAuthorisationApi serviceAuthorisation;
  @Autowired
  private Elsewhere elsewhere;

  @Test
  void idamUserInfoDescribesTheRegisteredActor() {
    String token = actors.register(ActorDetails.of("First", "Judge", "caseworker-pcs"));

    Map<String, Object> user = idam.userInfo(token);

    assertThat(user).containsEntry("given_name", "First").containsEntry("family_name", "Judge")
        .containsEntry("sub", "first.judge@example.com").containsEntry("roles", List.of("caseworker-pcs"));
    assertThat(user.get("uid")).isEqualTo(actors.require(token).uid());
  }

  @Test
  void idamRejectsATokenNoActorWasRegisteredFor() {
    assertThatThrownBy(() -> idam.userInfo("Bearer unknown")).isInstanceOf(FeignException.Unauthorized.class);
  }

  @Test
  void serviceTokenGeneratorLeasesAUsableToken() {
    var generator = AuthTokenGeneratorFactory.createDefaultGenerator("AAAAAAAAAAAAAAAA", "my-service",
        serviceAuthorisation);

    assertThat(generator.generate()).startsWith("Bearer ey");
  }

  @Test
  void roleAssignmentHasNoRolesForAnyone() {
    assertThat(roleAssignment.roles("any-actor")).containsEntry("roleAssignmentResponse", List.of());
  }

  @Test
  void otherCallsStillGoToTheirService() {
    assertThatThrownBy(() -> elsewhere.anything()).isInstanceOf(RetryableException.class);
  }

  @FeignClient(name = "idam", url = "http://idam.invalid")
  interface Idam {
    @GetMapping("/o/userinfo")
    Map<String, Object> userInfo(@RequestHeader("Authorization") String authorisation);
  }

  @FeignClient(name = "am", url = "http://am.invalid")
  interface RoleAssignment {
    @GetMapping("/am/role-assignments/actors/{id}")
    Map<String, Object> roles(@PathVariable("id") String actorId);
  }

  @FeignClient(name = "elsewhere", url = "http://elsewhere.invalid")
  interface Elsewhere {
    @GetMapping("/anything")
    String anything();
  }

  @SpringBootConfiguration
  @ImportAutoConfiguration({
      FeignAutoConfiguration.class,
      HttpMessageConvertersAutoConfiguration.class,
      Jackson2AutoConfiguration.class
  })
  @EnableFeignClients(clients = {Idam.class, RoleAssignment.class, Elsewhere.class, ServiceAuthorisationApi.class})
  @Import(CcdEventTestConfiguration.class)
  static class FeignApplication {
  }
}
