package uk.gov.hmcts.divorce.bundling;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.ccd.sdk.bundling.cdam.BundlingAuthenticationProvider;
import uk.gov.hmcts.divorce.idam.IdamService;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;

/**
 * The one bean the document-bundling auto-configuration cannot guess: how this service
 * authenticates to CDAM. Token wiring is service-specific by design — this service's IDAM system
 * user (idam.systemupdate.*) and its S2S identity (nfdiv_case_api) — so the SDK's CDAM
 * destination and resolver are fed from the service's existing IDAM and S2S clients.
 */
@Configuration
public class BundlingConfiguration {

    @Bean
    public BundlingAuthenticationProvider bundlingAuthenticationProvider(
            final IdamService idamService, final AuthTokenGenerator authTokenGenerator) {
        return new BundlingAuthenticationProvider() {

            @Override
            public String systemUserToken() {
                return idamService.retrieveSystemUpdateUserDetails().getAuthToken();
            }

            @Override
            public String serviceToken() {
                return authTokenGenerator.generate();
            }
        };
    }
}
