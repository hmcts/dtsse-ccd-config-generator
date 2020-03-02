package uk.gov.hmcts.reform.fpl.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.SuperBuilder;

import java.util.List;

@Data
@SuperBuilder
@JsonIgnoreProperties(ignoreUnknown = true)
public class Proceeding extends ProceedingType {
    private final String onGoingProceeding;
    private final List<ProceedingType> additionalProceedings;

}
