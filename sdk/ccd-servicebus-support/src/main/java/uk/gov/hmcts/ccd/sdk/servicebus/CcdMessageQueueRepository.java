package uk.gov.hmcts.ccd.sdk.servicebus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CcdMessageQueueRepository {

  private static final String SELECT_UNPUBLISHED = """
      SELECT id, reference, message_type, time_stamp, message_information
        FROM ccd.message_queue_candidates
       WHERE published IS NULL
         AND message_type = ?
       ORDER BY time_stamp
       LIMIT ?
       FOR UPDATE SKIP LOCKED
      """;

  private static final String UPDATE_PUBLISHED = """
      UPDATE ccd.message_queue_candidates
         SET published = ?
       WHERE id = ?
      """;

  private static final String DELETE_PUBLISHED = """
      DELETE FROM ccd.message_queue_candidates
       WHERE message_type = ?
         AND published < ?
      """;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public List<MessageQueueCandidate> findUnpublishedMessages(String messageType, int limit) {
    return jdbcTemplate.query(SELECT_UNPUBLISHED, rowMapper(), messageType, limit);
  }

  public void markPublished(List<Long> ids, LocalDateTime publishedAt) {
    if (ids == null || ids.isEmpty()) {
      return;
    }

    jdbcTemplate.batchUpdate(UPDATE_PUBLISHED, new BatchPreparedStatementSetter() {
      @Override
      public void setValues(PreparedStatement ps, int i) throws SQLException {
        ps.setObject(1, publishedAt);
        ps.setLong(2, ids.get(i));
      }

      @Override
      public int getBatchSize() {
        return ids.size();
      }
    });
  }

  public int deletePublishedBefore(String messageType, LocalDateTime cutoff) {
    return jdbcTemplate.update(DELETE_PUBLISHED, messageType, cutoff);
  }

  private RowMapper<MessageQueueCandidate> rowMapper() {
    return (ResultSet rs, int rowNum) -> new MessageQueueCandidate(
        rs.getLong("id"),
        rs.getLong("reference"),
        rs.getString("message_type"),
        rs.getTimestamp("time_stamp").toLocalDateTime(),
        toJsonNode(rs.getString("message_information"))
    );
  }

  private JsonNode toJsonNode(String rawJson) {
    try {
      return objectMapper.readTree(rawJson);
    } catch (JsonProcessingException e) {
      throw new DataRetrievalFailureException("Unable to parse message_information JSON", e);
    }
  }

  public record MessageQueueCandidate(
      long id,
      long reference,
      String messageType,
      LocalDateTime timestamp,
      JsonNode payload
  ) { }
}
