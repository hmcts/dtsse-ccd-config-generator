package uk.gov.hmcts.divorce.sow014.nfd;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

import static java.lang.System.getenv;
import static java.util.Collections.singletonList;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static uk.gov.hmcts.divorce.divorcecase.model.State.Draft;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.APPLICANT_1_SOLICITOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CITIZEN;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.LEGAL_ADVISOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SOLICITOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.type.YesOrNo;
import uk.gov.hmcts.divorce.common.ccd.PageBuilder;
import uk.gov.hmcts.divorce.divorcecase.model.ApplicationType;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;
import uk.gov.hmcts.divorce.idam.IdamService;
import uk.gov.hmcts.divorce.idam.User;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

@Slf4j
@Component
public class CreateTestCase implements CCDConfig<CaseData, State, UserRole> {
    private static final String ENVIRONMENT_AAT = "aat";
    private static final String TEST_CREATE = "create-test-application";
    private static final String SOLE_APPLICATION = "classpath:data/sole.json";
    private static final String JOINT_APPLICATION = "classpath:data/joint.json";
    public static volatile boolean submittedCallbackTriggered = false;
    public static volatile CaseData submitted;


    @Getter
    enum SolicitorRoles {

        CREATOR("ecb8fff1-e033-3846-b15e-c01ff10cb4bb", UserRole.CREATOR.getRole()),
        APPLICANT2("6e508b49-1fa8-3d3c-8b53-ec466637315b", UserRole.APPLICANT_2.getRole()),
        APPLICANT_2_SOLICITOR("b81df946-87c4-3eb8-95e0-2da70727aec8", UserRole.APPLICANT_2_SOLICITOR.getRole()),
        APPLICANT_1_SOLICITOR("74779774-2fc4-32c9-a842-f8d0aa6e770a",UserRole.APPLICANT_1_SOLICITOR.getRole()),
        CITIZEN("20fa35c5-167f-3d6f-b8ab-5c487d16f29d", UserRole.CITIZEN.getRole());

        private final String id;
        private final String role;

        SolicitorRoles(String id, String role) {
            this.id = id;
            this.role = role;
        }
    }

    @Autowired
    private HttpServletRequest httpServletRequest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdamService idamService;

    @Override
    public void configure(ConfigBuilder<CaseData, State, UserRole> configBuilder) {
        var roles = new ArrayList<UserRole>();

        roles.add(SOLICITOR);
        roles.add(CASE_WORKER);

        new PageBuilder(configBuilder
            .event(TEST_CREATE)
            .initialState(Draft)
            .aboutToStartCallback(this::start)
            .aboutToSubmitCallback(this::submit)
            .submittedCallback(this::submitted)
            .name("Create test case")
            .grant(CREATE_READ_UPDATE, roles.toArray(UserRole[]::new))
            .grantHistoryOnly(SUPER_USER, CASE_WORKER, LEGAL_ADVISOR, SOLICITOR, CITIZEN, JUDGE))
            .page("Create test case")
            .mandatory(CaseData::getApplicationType)
            .optional(CaseData::getTestDocument)
            .done();
    }

    private SubmittedCallbackResponse submitted(CaseDetails<CaseData, State> details,
                                                CaseDetails<CaseData, State> before) {
        submittedCallbackTriggered = true;
        submitted = details.getData();
        return SubmittedCallbackResponse.builder().build();
    }

    private AboutToStartOrSubmitResponse<CaseData, State> start(CaseDetails<CaseData, State> caseDetails) {
        var data = caseDetails.getData();
        data.setSetInAboutToStart("My custom value");
        return AboutToStartOrSubmitResponse.<CaseData, State>builder()
            .data(data)
            .build();
    }

    private AboutToStartOrSubmitResponse<CaseData, State> submit(CaseDetails<CaseData, State> details, CaseDetails<CaseData, State> before) {
        details.getData().getApplicant1().setFirstName("app1_first_name");
        details.getData().getApplicant2().setFirstName("app2_first_name");
        return AboutToStartOrSubmitResponse.<CaseData, State>builder()
            .data(details.getData())
            .state(State.Submitted)
            .build();
    }
}
