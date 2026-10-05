package uk.gov.hmcts.ccd.sdk.servicebus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
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

  // Locks the earliest unpublished message of each case, which gives this transaction ownership of the case.
  // A locked head is skipped, and its successors are never candidates in its place.
  private static final String CLAIM_CASE_HEADS = """
      WITH heads AS MATERIALIZED (
          SELECT DISTINCT ON (reference) id
            FROM ccd.message_queue_candidates
           WHERE published IS NULL
             AND message_type = ?
             AND NOT (reference = ANY(?))
           ORDER BY reference, id
      )
      SELECT q.id, q.reference
        FROM ccd.message_queue_candidates q
        JOIN heads ON heads.id = q.id
       WHERE q.published IS NULL
         AND q.message_type = ?
       ORDER BY q.time_stamp, q.id
       LIMIT ?
       FOR UPDATE OF q SKIP LOCKED
      """;

  // Fetches messages only for owned cases, in id order. No SKIP LOCKED so every case's messages are a prefix.
  // Joining the references rather than filtering with = ANY keeps the plan on the partial index; with = ANY
  // a deep backlog for one case can make the planner scan the primary key instead.
  private static final String SELECT_OWNED_UNPUBLISHED = """
      SELECT q.id, q.reference, q.message_type, q.time_stamp, q.message_information
        FROM ccd.message_queue_candidates q
        JOIN (SELECT DISTINCT reference FROM unnest(?) AS owned(reference)) owned
          ON owned.reference = q.reference
       WHERE q.published IS NULL
         AND q.message_type = ?
       ORDER BY q.id
       LIMIT ?
       FOR UPDATE OF q
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

  /**
   * Claims the earliest unpublished message of up to {@code limit} cases, excluding the given cases.
   * The claim lasts until the surrounding transaction ends.
   */
  public List<CaseHead> claimCaseHeads(String messageType, Collection<Long> excludedReferences, int limit) {
    return jdbcTemplate.query(
        CLAIM_CASE_HEADS,
        ps -> {
          ps.setString(1, messageType);
          ps.setArray(2, ps.getConnection().createArrayOf("bigint", excludedReferences.toArray()));
          ps.setString(3, messageType);
          ps.setInt(4, limit);
        },
        (rs, rowNum) -> new CaseHead(rs.getLong("id"), rs.getLong("reference")));
  }

  public List<MessageQueueCandidate> findOwnedUnpublishedMessages(String messageType,
                                                                  Collection<Long> ownedReferences,
                                                                  int limit) {
    return jdbcTemplate.query(
        SELECT_OWNED_UNPUBLISHED,
        ps -> {
          ps.setArray(1, ps.getConnection().createArrayOf("bigint", ownedReferences.toArray()));
          ps.setString(2, messageType);
          ps.setInt(3, limit);
        },
        rowMapper());
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

  public record CaseHead(long id, long reference) { }

  public record MessageQueueCandidate(
      long id,
      long reference,
      String messageType,
      LocalDateTime timestamp,
      JsonNode payload
  ) { }
}
