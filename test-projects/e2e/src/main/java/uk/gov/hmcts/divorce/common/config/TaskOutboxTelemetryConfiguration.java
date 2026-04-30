package uk.gov.hmcts.divorce.common.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.ccd.sdk.taskmanagement.TaskOutboxRetriesExhaustedEvent;
import uk.gov.hmcts.ccd.sdk.taskmanagement.TaskOutboxTelemetry;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@Slf4j
public class TaskOutboxTelemetryConfiguration {

    private static final String EVENT_NAME = "TaskOutboxHeadRecordExhausted";
    private static final String TELEMETRY_CLIENT_CLASS = "com.microsoft.applicationinsights.TelemetryClient";

    private Object telemetryClient;
    private Method trackEventMethod;

    @PostConstruct
    void initialiseTelemetryClient() {
        try {
            Class<?> clientClass = Class.forName(TELEMETRY_CLIENT_CLASS);
            this.telemetryClient = clientClass.getDeclaredConstructor().newInstance();
            this.trackEventMethod = clientClass.getMethod("trackEvent", String.class, Map.class, Map.class);
            log.info("Task outbox App Insights telemetry enabled using {}", TELEMETRY_CLIENT_CLASS);
        } catch (Exception ex) {
            this.telemetryClient = null;
            this.trackEventMethod = null;
            log.warn(
                "App Insights TelemetryClient not available on classpath; "
                    + "TaskOutboxHeadRecordExhausted will only be logged",
                ex
            );
        }
    }

    @Bean
    public TaskOutboxTelemetry taskOutboxTelemetry() {
        return this::emitRetriesExhaustedEvent;
    }

    private void emitRetriesExhaustedEvent(TaskOutboxRetriesExhaustedEvent event) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("caseId", String.valueOf(event.record().caseId()));
        properties.put("taskOutboxId", String.valueOf(event.record().id()));
        properties.put("requestedAction", event.record().requestedAction());
        properties.put("attemptCount", String.valueOf(event.record().attemptCount()));
        properties.put("maxAttempts", String.valueOf(event.maxAttempts()));
        properties.put("lastStatusCode", event.statusCode() == null ? "unknown" : String.valueOf(event.statusCode()));
        properties.put(
            "nextRetryableOutboxId",
            event.nextRetryableOutboxId() == null ? "none" : String.valueOf(event.nextRetryableOutboxId())
        );
        properties.put("service", "e2e-case-api");
        properties.put("eventType", "task-outbox-retry-exhausted");
        log.warn("{} {}", EVENT_NAME, properties);

        if (telemetryClient != null && trackEventMethod != null) {
            try {
                trackEventMethod.invoke(telemetryClient, EVENT_NAME, properties, null);
            } catch (Exception ex) {
                log.warn("Failed to emit App Insights event {}", EVENT_NAME, ex);
            }
        }
    }
}
