package com.wisewallet.transaction.infrastructure.client.config;

import feign.Request;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class AccountServiceFeignConfig {

    @Value("${wisewallet.internal.api-key:default-internal-api-key}")
    private String internalApiKey;

    @Bean
    public RequestInterceptor internalKeyInterceptor() {
        return requestTemplate -> requestTemplate.header("X-Internal-Key", internalApiKey);
    }

    @Bean
    public Request.Options requestOptions() {
        return new Request.Options(2, TimeUnit.SECONDS, 5, TimeUnit.SECONDS, true);
    }
}
