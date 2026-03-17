package uk.gov.hmcts.ccd.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.Test;

public class DtoMapperTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  public void fromCcdDataTreatsNullMapAsEmpty() {
    TestDto result = DtoMapper.fromCcdData(null, "claimCreate", TestDto.class, mapper);

    assertThat(result).isNotNull();
    assertThat(result.getName()).isNull();
  }

  @Test
  public void toCcdDataTreatsNullDtoAsEmpty() {
    assertThat(DtoMapper.toCcdData(null, "claimCreate", mapper)).isEmpty();
  }

  @Test
  public void mapsUsingNamespaceDerivedPrefixStem() {
    TestDto result = DtoMapper.fromCcdData(
        Map.of("claimCreateName", "example"),
        "claimCreate",
        TestDto.class,
        mapper
    );

    assertThat(result.getName()).isEqualTo("example");
    assertThat(DtoMapper.toCcdData(result, "claimCreate", mapper))
        .containsEntry("claimCreateName", "example");
  }

  public static class TestDto {
    private String name;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }
}
