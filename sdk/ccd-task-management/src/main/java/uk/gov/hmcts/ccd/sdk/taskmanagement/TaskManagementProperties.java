package uk.gov.hmcts.ccd.sdk.taskmanagement;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "task-management")
public class TaskManagementProperties {

    private Api api = new Api();
    private Outbox outbox = new Outbox();

    @Data
    public static class Api {
        private String url;
    }

    @Data
    public static class Outbox {
        private String schema = "ccd";
        private Poller poller = new Poller();
        private Retry retry = new Retry();
    }

    @Data
    public static class Poller {
        private int batchSize = 5;
    }

    @Data
    public static class Retry {
        private Duration initialDelay = Duration.ofSeconds(1);
        private Duration maxDelay = Duration.ofMinutes(5);
        private double multiplier = 2.0;
        private int maxAttempts = 0;
    }
}
