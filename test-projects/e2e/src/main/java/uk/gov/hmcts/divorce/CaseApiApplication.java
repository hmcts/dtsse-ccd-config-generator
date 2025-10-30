package uk.gov.hmcts.divorce;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;
import uk.gov.hmcts.reform.authorisation.ServiceAuthorisationApi;
import uk.gov.hmcts.reform.ccd.client.CaseAssignmentApi;
import uk.gov.hmcts.reform.ccd.client.CaseEventsApi;
import uk.gov.hmcts.reform.ccd.client.CaseUserApi;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;
import uk.gov.hmcts.reform.idam.client.IdamApi;

import java.util.TimeZone;

@SpringBootApplication(
    scanBasePackages = {"uk.gov.hmcts.ccd.sdk", "uk.gov.hmcts.divorce", "uk.gov.hmcts.reform.idam.client",
        "uk.gov.hmcts.reform.sendletter", "uk.gov.hmcts.reform.ccd.document.am.feign"}
)
@EnableFeignClients(
    clients = {
        IdamApi.class,
        ServiceAuthorisationApi.class,
        CaseUserApi.class,
        CoreCaseDataApi.class,
        CaseAssignmentApi.class,
        CaseDocumentClientApi.class,
        CaseEventsApi.class,
    }
)
@EnableScheduling
@EnableRetry
@SuppressWarnings("HideUtilityClassConstructor") // Spring needs a constructor, its not a utility class
@Slf4j
public class CaseApiApplication {

    public static void main(final String[] args) {
        final var application = new SpringApplication(CaseApiApplication.class);
        final var instance = application.run(args);

        if (System.getenv("TASK_NAME") != null) {
            instance.close();
        }
    }


    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));
    }
}
