package uk.gov.hmcts.divorce.bundling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.bundling.api.CcdBundle;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.divorce.bundling.model.CaseBundle;

/**
 * Service-owned persistence for generated bundles, following the decentralised idiom used by
 * case notes: the submit handler saves inside the event transaction and {@code NFDCaseView}
 * projects the rows into the external {@code caseBundles} case field. The SDK's {@link CcdBundle}
 * output is stored verbatim and read back as this service's {@link CaseBundle} model — the two
 * are JSON-compatible by design.
 */
@Component
public class CaseBundleRepository {

    @Autowired
    private NamedParameterJdbcTemplate db;

    @Autowired
    private ObjectMapper mapper;

    public void save(final long caseReference, final String bundleId, final CcdBundle bundle) {
        try {
            db.update(
                "insert into case_bundles(reference, bundle_id, bundle)"
                    + " values (:reference, :bundleId, :bundle::jsonb)",
                new MapSqlParameterSource()
                    .addValue("reference", caseReference)
                    .addValue("bundleId", bundleId)
                    .addValue("bundle", mapper.writeValueAsString(bundle))
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise bundle " + bundleId, e);
        }
    }

    public List<ListValue<CaseBundle>> findByCase(final long caseReference) {
        return db.query(
            "select bundle_id, bundle from case_bundles where reference = :reference order by id",
            new MapSqlParameterSource().addValue("reference", caseReference),
            (rs, rowNum) -> {
                try {
                    return new ListValue<>(
                        rs.getString("bundle_id"),
                        mapper.readValue(rs.getString("bundle"), CaseBundle.class));
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("Could not deserialise stored bundle", e);
                }
            }
        );
    }
}
