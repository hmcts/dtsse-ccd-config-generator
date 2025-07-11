package uk.gov.hmcts.ccd.sdk;

import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyEnforcer {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final NamedParameterJdbcTemplate db;

    public boolean markProcessedReturningIsAlreadyProcessed(String idempotencyKey) {
        int rowsAffected = db.update(
            "insert into ccd.completed_events (id) values (:id) on conflict(id) do nothing",
            Map.of(
                "id", UUID.fromString(idempotencyKey)
            )
        );

        if (rowsAffected == 0) {
            log.info("Idempotency key '{}' already exists. Request previously processed.", idempotencyKey);
            return true;
        } else {
            log.info("Idempotency key '{}' successfully recorded. First time processing request.", idempotencyKey);
            return false;
        }
    }
}
