package uk.gov.hmcts.reform.fpl.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.List;

@Data
@Builder
@AllArgsConstructor(onConstructor_ = {@JsonCreator})
public class GroundsForEPO {
    @NotNull(message = "Select at least one option for how this case meets grounds for an emergency protection order")
    @Size(min = 1,
        message = "Select at least one option for how this case meets grounds for an emergency protection order")
    private List<@NotBlank(
        message = "Select at least one option for how this case meets grounds for an emergency protection order") String> reason;
}
