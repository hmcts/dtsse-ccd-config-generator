package uk.gov.hmcts.ccd.sdk.docweave.templates;

import static java.util.stream.Collectors.joining;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.http.HttpStatus;
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
    return jdbc.queryForObject(
        """
        insert into docweave.docweave_template
          (id, owner_id, title, content, tags, searchable_text)
        select :id::uuid, :owner::uuid, :title, :content::jsonb, new.tags,
               :searchableText || chr(10) || array_to_string(new.tags, ' ')
        from (select array(select jsonb_array_elements_text(:tags::jsonb)) as tags) new
        returning *
        """,
        parameters(owner, title, content, tags, searchableText)
            .addValue("id", UUID.randomUUID().toString()),
        this::mapRow
    );
  }

  public List<Template> search(UUID user, String query, boolean mine) {
    String patterns = searchTerms(query).stream()
        .map(term -> "%" + term.replaceAll("([\\\\%_])", "\\\\$1") + "%")
        .collect(joining("\n"));
    return jdbc.query(
        """
        select *
        from docweave.docweave_template
        where (:owner::uuid is null or owner_id = :owner::uuid)
          and searchable_text ilike all(string_to_array(:patterns, chr(10)))
        order by lower(title), id
        """,
        new MapSqlParameterSource("owner", mine ? user.toString() : null)
            .addValue("patterns", patterns),
        this::mapRow
    );
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
    MapSqlParameterSource parameters = parameters(owner, title, content, tags, searchableText)
        .addValue("id", id.toString())
        .addValue("revision", expectedRevision)
        .addValue("replaceTags", tags != null);
    List<Template> updated = jdbc.query(
        """
        update docweave.docweave_template template
        set title = :title,
            content = :content::jsonb,
            tags = new.tags,
            searchable_text = :searchableText || chr(10) || array_to_string(new.tags, ' '),
            revision = revision + 1,
            updated_at = now()
        from (select case when :replaceTags::boolean
                then array(select jsonb_array_elements_text(:tags::jsonb))
                else (select tags from docweave.docweave_template where id = :id::uuid)
              end as tags) new
        where template.id = :id::uuid
          and template.owner_id = :owner::uuid
          and template.revision = :revision
        returning template.*
        """,
        parameters,
        this::mapRow
    );
    if (updated.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found");
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
        delete from docweave.docweave_template
        where id = :id::uuid and owner_id = :owner::uuid and revision = :revision
        """,
        parameters
    );
    if (deleted == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found");
    }
  }

  private MapSqlParameterSource parameters(
      UUID owner, String title, JsonNode content, List<String> tags, String searchableText
  ) {
    return new MapSqlParameterSource()
        .addValue("owner", owner.toString())
        .addValue("title", title)
        .addValue("content", content == null ? null : content.toString())
        .addValue("tags", json.valueToTree(tags == null ? List.of() : tags).toString())
        .addValue("searchableText", searchableText == null ? title : title + "\n" + searchableText);
  }

  @SneakyThrows
  private Template mapRow(ResultSet rs, int row) {
    return new Template(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("owner_id")),
        rs.getString("title"),
        json.readTree(rs.getString("content")),
        readTags(rs.getArray("tags")),
        rs.getLong("revision"),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant()
    );
  }

  private List<String> readTags(Array tags) throws SQLException {
    return tags == null ? List.of() : List.of((String[]) tags.getArray());
  }

  private List<String> searchTerms(String query) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    return List.of(query.trim().split("\\s+"));
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

}
