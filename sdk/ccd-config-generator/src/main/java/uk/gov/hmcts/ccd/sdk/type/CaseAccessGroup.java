package uk.gov.hmcts.ccd.sdk.type;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * An entry in the {@code CaseAccessGroups} collection that data store maintains in case data.
 *
 * <p>On case create and on each event save, data store derives one entry per AccessTypeRole with a
 * group role: it substitutes the organisation ID from the matching OrganisationPolicy into the
 * row's {@code CaseAccessGroupIDTemplate} and writes the result as {@code caseAccessGroupId},
 * under {@code caseAccessGroupType} {@code "CCD:all-cases-access"}. A group role assignment grants
 * access to the case when its {@code caseAccessGroupId} attribute matches one of these entries.</p>
 *
 * <p>Declare the collection on your case model to have the field generated, so the values can be
 * displayed and debugged:</p>
 *
 * <pre>
 * &#64;JsonProperty("CaseAccessGroups")
 * private List&lt;ListValue&lt;CaseAccessGroup&gt;&gt; caseAccessGroups;
 * </pre>
 *
 * <p>The JSON property name must be {@code CaseAccessGroups} to match what data store writes.
 * The complex type itself is predefined by the definition store, so it is not generated here.</p>
 */
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
@ComplexType(name = "CaseAccessGroup", generate = false)
public class CaseAccessGroup {

  private String caseAccessGroupType;

  private String caseAccessGroupId;
}
