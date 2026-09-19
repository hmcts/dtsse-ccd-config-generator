package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

public class CcdCaseDataMapperTest {

  @Test
  public void preservesPropertyDeclarationOrder() throws Exception {
    var builder = JsonMapper.builder()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

    CcdCaseDataMapper.configure(builder);

    assertThat(builder.build().writeValueAsString(new OrderedCaseData()))
        .isEqualTo("{\"second\":\"two\",\"first\":\"one\"}");
  }

  private static class OrderedCaseData {
    public String second = "two";
    public String first = "one";
  }
}
