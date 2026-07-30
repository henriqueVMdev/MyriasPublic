package com.myrias.mlmanager.ai;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** RestClient dedicado do OpenRouter — o modelo pode levar minutos com tools. */
@Configuration
public class AiConfig {

    @Bean
    RestClient openRouterRestClient(
            @Value("${openrouter.base-url:https://openrouter.ai/api/v1}") String baseUrl) {
        var settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofSeconds(120));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }
}
