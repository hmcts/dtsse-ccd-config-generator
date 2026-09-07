package uk.gov.hmcts.ccd.sdk.docweave.templates;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@RequiredArgsConstructor
public class DocweaveTemplateRepository {

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper json;

  public Template create(
      UUID owner,
      String title,
      JsonNode content,
      List<String> tags,
      String searchableText
  ) {
    Prepared input = prepare(title, content, tags, searchableText);
    return jdbc.queryForObject(
        """
        insert into ccd.docweave_template
          (id, owner_id, title, content, tags, searchable_text)
        values
          (:id::uuid, :owner::uuid, :title, :content::jsonb,
           array(select jsonb_array_elements_text(:tags::jsonb)), :searchableText)
        returning *
        """,
        parameters(owner, input)
            .addValue("id", UUID.randomUUID().toString()),
        rowMapper()
    );
  }

  public Page search(UUID user, String query, int requestedSize, String cursor, boolean mine) {
    int size = Math.min(requestedSize, 100);
    int offset = cursor == null || cursor.isBlank() ? 0 : parseCursor(cursor);
    List<Term> terms = searchTerms(query);
    MapSqlParameterSource parameters = new MapSqlParameterSource()
        .addValue("owner", mine ? user.toString() : null)
        .addValue("terms", write(terms))
        .addValue("limit", size + 1)
        .addValue("offset", offset);

    List<Template> found = jdbc.query(
        """
        with search_terms as (
          select *
          from jsonb_to_recordset(:terms::jsonb) as term(exact text, pattern text)
        )
        select template.*
        from ccd.docweave_template template
        where (:owner::uuid is null or template.owner_id = :owner::uuid)
          and not exists (
            select 1
            from search_terms term
            where not (
              template.tags @> array[term.exact]
              or template.title ilike term.pattern escape '\\'
              or template.searchable_text ilike term.pattern escape '\\'
            )
          )
        order by (
          select coalesce(sum(
            case when template.tags @> array[term.exact] then 100 else 0 end
            + ccd.word_similarity(term.exact, template.title::text) * 10
            + ccd.word_similarity(term.exact, template.searchable_text)
          ), 0)
          from search_terms term
        ) desc, template.id desc
        limit :limit
        offset :offset
        """,
        parameters,
        rowMapper()
    );

    List<Template> items = found.size() > size ? found.subList(0, size) : found;
    String nextCursor = found.size() > size ? String.valueOf(offset + size) : null;
    return new Page(items, nextCursor);
  }

  @Transactional
  public Template update(
      UUID id,
      UUID owner,
      long expectedRevision,
      String title,
      JsonNode content,
      List<String> tags,
      String searchableText
  ) {
    Prepared input = prepare(title, content, tags, searchableText);
    MapSqlParameterSource parameters = parameters(owner, input)
        .addValue("id", id.toString())
        .addValue("revision", expectedRevision)
        .addValue("replaceTags", tags != null);
    List<Template> updated = jdbc.query(
        """
        update ccd.docweave_template
        set title = :title,
            content = :content::jsonb,
            tags = case when :replaceTags::boolean
              then array(select jsonb_array_elements_text(:tags::jsonb)) else tags end,
            searchable_text = :searchableText,
            revision = revision + 1,
            updated_at = now()
        where id = :id::uuid and owner_id = :owner::uuid and revision = :revision
        returning *
        """,
        parameters,
        rowMapper()
    );
    if (updated.isEmpty()) {
      throw mutationError(id, owner);
    }
    return updated.getFirst();
  }

  @Transactional
  public void delete(UUID id, UUID owner, long expectedRevision) {
    MapSqlParameterSource parameters = new MapSqlParameterSource()
        .addValue("id", id.toString())
        .addValue("owner", owner.toString())
        .addValue("revision", expectedRevision);
    int deleted = jdbc.update(
        """
        delete from ccd.docweave_template
        where id = :id::uuid and owner_id = :owner::uuid and revision = :revision
        """,
        parameters
    );
    if (deleted == 0) {
      throw mutationError(id, owner);
    }
  }

  private Prepared prepare(String title, JsonNode content, List<String> tags, String searchableText) {
    LinkedHashSet<String> cleanTags = new LinkedHashSet<>();
    if (tags != null) {
      tags.stream()
          .filter(tag -> tag != null && !tag.isBlank())
          .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
          .forEach(cleanTags::add);
    }
    List<String> normalizedTags = List.copyOf(cleanTags);
    String cleanTitle = title == null ? null : title.trim();
    String suppliedText = searchableText == null ? "" : searchableText.trim();
    return new Prepared(
        cleanTitle,
        content,
        normalizedTags,
        suppliedText
    );
  }

  private ResponseStatusException mutationError(UUID id, UUID owner) {
    Optional<UUID> actualOwner = jdbc.query(
        "select owner_id from ccd.docweave_template where id = :id::uuid",
        new MapSqlParameterSource("id", id.toString()),
        (rs, row) -> UUID.fromString(rs.getString(1))
    ).stream().findFirst();
    if (actualOwner.isEmpty()) {
      return new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found");
    }
    if (!actualOwner.get().equals(owner)) {
      return new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the template owner can modify it");
    }
    return new ResponseStatusException(HttpStatus.CONFLICT, "Template revision is stale");
  }

  private MapSqlParameterSource parameters(UUID owner, Prepared input) {
    return new MapSqlParameterSource()
        .addValue("owner", owner.toString())
        .addValue("title", input.title())
        .addValue("content", input.content() == null ? null : write(input.content()))
        .addValue("tags", write(input.tags()))
        .addValue("searchableText", input.searchableText());
  }

  private RowMapper<Template> rowMapper() {
    return (ResultSet rs, int row) -> new Template(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("owner_id")),
        rs.getString("title"),
        read(rs.getString("content")),
        readTags(rs.getArray("tags")),
        rs.getLong("revision"),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant()
    );
  }

  private List<String> readTags(Array tags) throws SQLException {
    return tags == null ? List.of() : List.of((String[]) tags.getArray());
  }

  private String escape(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private List<Term> searchTerms(String query) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    String trimmed = query.trim();
    if (trimmed.length() < 3) {
      throw badRequest("Search query must contain at least 3 characters");
    }
    return List.of(trimmed.split("\\s+")).stream()
        .map(term -> term.toLowerCase(Locale.ROOT))
        .map(term -> new Term(term, "%" + escape(term) + "%"))
        .toList();
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw badRequest("Invalid template content");
    }
  }

  private JsonNode read(String value) {
    try {
      return json.readTree(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Invalid stored template content", ex);
    }
  }

  private int parseCursor(String cursor) {
    try {
      int offset = Integer.parseInt(cursor);
      if (offset < 0) {
        throw new NumberFormatException();
      }
      return offset;
    } catch (RuntimeException ex) {
      throw badRequest("Invalid search cursor");
    }
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  public record Template(
      UUID id,
      UUID ownerId,
      String title,
      JsonNode content,
      List<String> tags,
      long revision,
      Instant updatedAt
  ) {
  }

  public record Page(List<Template> items, String nextCursor) {
  }

  private record Prepared(
      String title,
      JsonNode content,
      List<String> tags,
      String searchableText
  ) {
  }

  private record Term(String exact, String pattern) {
  }
}
