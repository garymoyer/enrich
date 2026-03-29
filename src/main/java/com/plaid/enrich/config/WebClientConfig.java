package com.plaid.enrich.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.LoggingCodecSupport;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for WebClient with optimized connection pooling and timeouts.
 * Configures a production-ready HTTP client for Plaid API integration.
 */
@Configuration
@Slf4j
public class WebClientConfig {

    @Value("${plaid.api.base-url}")
    private String plaidApiBaseUrl;

    @Value("${plaid.api.timeout.connection:5000}")
    private int connectionTimeout;

    @Value("${plaid.api.timeout.request:10000}")
    private int requestTimeout;

    /**
     * Creates a configured WebClient bean for Plaid API.
     * Features:
     * - Connection pooling (max 100 connections)
     * - Connection timeout (5s default)
     * - Read/write timeout (10s default)
     * - Request/response logging
     * - Large buffer size for Plaid responses
     */
    @Bean
    public WebClient plaidWebClient() {
        // Connection pooling configuration
        ConnectionProvider connectionProvider = ConnectionProvider.builder("plaid-pool")
                .maxConnections(100)
                .maxIdleTime(Duration.ofSeconds(20))
                .maxLifeTime(Duration.ofMinutes(5))
                .pendingAcquireTimeout(Duration.ofSeconds(45))
                .evictInBackground(Duration.ofSeconds(120))
                .build();

        // HTTP client with timeout settings
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectionTimeout)
                .responseTimeout(Duration.ofMillis(requestTimeout))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(requestTimeout, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(requestTimeout, TimeUnit.MILLISECONDS))
                );

        // Exchange strategies for larger buffer sizes
        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)) // 16MB
                .build();

        // Enable logging
        exchangeStrategies
                .messageWriters().stream()
                .filter(LoggingCodecSupport.class::isInstance)
                .forEach(writer -> ((LoggingCodecSupport) writer).setEnableLoggingRequestDetails(true));

        return WebClient.builder()
                .baseUrl(plaidApiBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(exchangeStrategies)
                .filter(logRequest())
                .filter(logResponse())
                .build();
    }

    /**
     * Logs outgoing requests.
     */
    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            if (log.isDebugEnabled()) {
                log.debug("Request: {} {}", clientRequest.method(), clientRequest.url());
                clientRequest.headers().forEach((name, values) ->
                        values.forEach(value -> log.debug("{}={}", name, value))
                );
            }
            return Mono.just(clientRequest);
        });
    }

    /**
     * Logs incoming responses.
     */
    private ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(clientResponse -> {
            if (log.isDebugEnabled()) {
                log.debug("Response Status: {}", clientResponse.statusCode());
            }
            return Mono.just(clientResponse);
        });
    }
}
