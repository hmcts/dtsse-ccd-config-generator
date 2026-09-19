package uk.gov.hmcts.divorce.common.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.converter.autoconfigure.ClientHttpMessageConvertersCustomizer;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.springframework.cloud.openfeign.support.HttpMessageConverterCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignConfiguration {

    @Bean
    FeignHttpMessageConverters feignHttpMessageConverters(
        ObjectProvider<ClientHttpMessageConvertersCustomizer> clientCustomizers,
        ObjectProvider<HttpMessageConverterCustomizer> feignCustomizers
    ) {
        return new FeignHttpMessageConverters(clientCustomizers, feignCustomizers);
    }
}
