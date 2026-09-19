package uk.gov.hmcts.ccd.sdk.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.CcdCaseDataMapper;

@AutoConfiguration
public class CcdCaseDataMapperConfiguration {

  public static final String CCD_CASE_DATA_OBJECT_MAPPER = "ccdCaseDataObjectMapper";

  /**
   * ObjectMapper used when serialising case data back to CCD's JSON wire shape.
   */
  @Bean(name = CCD_CASE_DATA_OBJECT_MAPPER)
  public ObjectMapper ccdCaseDataObjectMapper(ObjectMapper mapper) {
    // NON_NULL will retain eg. empty maps, required by certain contracts eg. AAC service & notice of change.
    var caseDataMapperBuilder = mapper.rebuild();
    CcdCaseDataMapper.configure(caseDataMapperBuilder);
    return caseDataMapperBuilder
      .changeDefaultPropertyInclusion(inclusion -> JsonInclude.Value.construct(
          JsonInclude.Include.NON_NULL,
          JsonInclude.Include.NON_NULL
      ))
      .addMixIn(CaseDetails.class, IgnoreUnknownCcdCaseDetails.class)
      .build();
  }

  @Bean
  public JsonMapperBuilderCustomizer ccdCaseDetailsMixin() {
    return builder -> builder.addMixIn(CaseDetails.class, IgnoreUnknownCcdCaseDetails.class);
  }

  @Bean
  public WebMvcConfigurer ccdPersistenceJacksonConverter(JsonMapper mapper) {
    return new WebMvcConfigurer() {
      @Override
      @SuppressWarnings("removal")
      public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(0, new CcdPersistenceJacksonConverter(mapper));
      }
    };
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private interface IgnoreUnknownCcdCaseDetails {
  }

  private static final class CcdPersistenceJacksonConverter extends JacksonJsonHttpMessageConverter {
    private static final String CCD_DTO_PACKAGE = "uk.gov.hmcts.ccd.decentralised.dto.";
    private static final String SUPPLEMENTARY_DATA_REQUEST =
        "uk.gov.hmcts.ccd.sdk.impl.SupplementaryDataUpdateRequest";

    private CcdPersistenceJacksonConverter(JsonMapper mapper) {
      super(mapper);
    }

    @Override
    public boolean canRead(ResolvableType type, MediaType mediaType) {
      return isCcdPersistenceType(type) && super.canRead(type, mediaType);
    }

    @Override
    public boolean canWrite(ResolvableType type, Class<?> valueClass, MediaType mediaType) {
      return isCcdPersistenceType(type) && super.canWrite(type, valueClass, mediaType);
    }

    private static boolean isCcdPersistenceType(ResolvableType type) {
      Class<?> resolved = type.resolve();
      if (resolved != null && (resolved.getName().startsWith(CCD_DTO_PACKAGE)
          || resolved.getName().equals(SUPPLEMENTARY_DATA_REQUEST))) {
        return true;
      }
      for (ResolvableType generic : type.getGenerics()) {
        if (isCcdPersistenceType(generic)) {
          return true;
        }
      }
      return false;
    }
  }
}
