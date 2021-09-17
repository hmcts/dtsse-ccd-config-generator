package uk.gov.hmcts.reform.fpl;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.function.Consumer;

@Data
@NoArgsConstructor
public class RetiredFields {

  private String orderAppliesToAllChildren;

  @JsonIgnore
  private static final Map<String, Consumer<Map<String, Object>>> migrations = Map.of(
    "orderAppliesToAllChildren", data -> data.put("caseLocalAuthority", data.get("orderAppliesToAllChildren"))
  );

  public static Map<String, Object> migrate(Map<String, Object> data) {

    for (String key : migrations.keySet()) {
      if (data.containsKey(key) && null != data.get(key)) {
        migrations.get(key).accept(data);
        data.put(key, null);
      }
    }

    return data;
  }
}
