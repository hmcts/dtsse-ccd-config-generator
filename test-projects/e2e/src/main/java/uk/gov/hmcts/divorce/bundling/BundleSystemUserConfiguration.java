package uk.gov.hmcts.divorce.bundling;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocToolkitSystemUser;
import uk.gov.hmcts.divorce.idam.IdamService;

/** The toolkit fetches and uploads CDAM documents as the system update user. */
@Configuration
public class BundleSystemUserConfiguration {

    @Bean
    public DocToolkitSystemUser docToolkitSystemUser(final IdamService idamService) {
        return () -> idamService.retrieveSystemUpdateUserDetails().getAuthToken();
    }
}
