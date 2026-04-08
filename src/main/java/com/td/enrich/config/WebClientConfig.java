package com.td.enrich.config;

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
 * Creates and configures the {@link WebClient} bean used by
 * {@link com.td.enrich.service.PlaidApiClient} to make HTTP calls to the Plaid API.
 *
 * <p>Spring Boot provides a basic {@code WebClient.Builder} out of the box, but
 * production services need additional configuration that isn't set by default:
 * <ul>
 *   <li><b>Connection pooling</b> — reusing existing TCP connections is far cheaper
 *       than opening a new one for every request. The pool here holds up to 100
 *       connections and evicts idle ones after 20 seconds.</li>
 *   <li><b>Timeouts</b> — without a timeout, a slow or hung Plaid server would cause
 *       worker threads to wait forever, eventually exhausting all available threads.
 *       We set both a TCP connect timeout and a read/write timeout.</li>
 *   <li><b>Buffer size</b> — Plaid responses can be large (batch results). The default
 *       WebFlux buffer is 256 KB; we increase it to 16 MB to avoid buffer overflow errors.</li>
 *   <li><b>Request/response logging</b> — at DEBUG level, every outbound URL and
 *       every inbound status code is logged. This is disabled in production by default
 *       ({@code logging.level.com.td.enrich: INFO}) and can be enabled temporarily for
 *       troubleshooting without a code change.</li>
 * </ul>
 *
 * <p>The {@code @Configuration} annotation tells Spring to treat this class as a source
 * of bean definitions. The {@code @Bean} method below is called once at startup and
 * its return value is stored in the Spring context, then injected wherever
 * {@code WebClient plaidWebClient} is declared as a dependency.
 */
@Configuration
@Slf4j
public class WebClientConfig {

    /** Base URL of the Plaid API. Read from {@code plaid.api.base-url} in application.yml. */
    @Value("${plaid.api.base-url}")
    private String plaidApiBaseUrl;

    /**
     * How long (in milliseconds) to wait for a TCP connection to be established.
     * Default: 5000 ms (5 seconds). Overridable via {@code plaid.api.timeout.connection}.
     */
    @Value("${plaid.api.timeout.connection:5000}")
    private int connectionTimeout;

    /**
     * How long (in milliseconds) to wait for a response after sending a request.
     * Default: 10000 ms (10 seconds). Overridable via {@code plaid.api.timeout.request}.
     */
    @Value("${plaid.api.timeout.request:10000}")
    private int requestTimeout;

    /**
     * Builds the fully configured {@code WebClient} for Plaid API calls.
     *
     * <p>The construction order matters:
     * <ol>
     *   <li>Build a {@link ConnectionProvider} with pool limits.</li>
     *   <li>Build a Reactor Netty {@link HttpClient} with that pool and all timeout settings.</li>
     *   <li>Wrap it in a {@link ReactorClientHttpConnector} so Spring's WebClient can use it.</li>
     *   <li>Set a generous in-memory buffer size via {@link ExchangeStrategies}.</li>
     *   <li>Add request and response logging filters.</li>
     * </ol>
     *
     * @return a singleton {@code WebClient} instance wired to the Plaid API base URL
     */
    @Bean
    public WebClient plaidWebClient() {

        // --- Step 1: Configure the TCP connection pool ---
        // "pending acquire timeout" is how long a caller waits to borrow a connection
        // from the pool before giving up. Set lower than the overall request timeout.
        ConnectionProvider connectionProvider = ConnectionProvider.builder("plaid-pool")
                .maxConnections(100)
                .maxIdleTime(Duration.ofSeconds(20))       // close connections idle for 20 s
                .maxLifeTime(Duration.ofMinutes(5))        // always close connections older than 5 min
                .pendingAcquireTimeout(Duration.ofSeconds(45))
                .evictInBackground(Duration.ofSeconds(120))
                .build();

        // --- Step 2: Configure the Netty HTTP client with timeouts ---
        // CONNECT_TIMEOUT_MILLIS: max time to open the TCP connection
        // responseTimeout: max time from sending the request to receiving the first byte of response
        // ReadTimeoutHandler / WriteTimeoutHandler: enforce per-chunk timeouts on the Netty pipeline
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectionTimeout)
                .responseTimeout(Duration.ofMillis(requestTimeout))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(requestTimeout, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(requestTimeout, TimeUnit.MILLISECONDS))
                );

        // --- Step 3: Increase the in-memory buffer limit ---
        // Plaid batch responses can be large. The default WebFlux limit of 256 KB would
        // throw a DataBufferLimitException for large responses, so we increase it to 16 MB.
        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();

        // Optionally enable detailed request body logging (controlled by log level)
        exchangeStrategies
                .messageWriters().stream()
                .filter(LoggingCodecSupport.class::isInstance)
                .forEach(writer -> ((LoggingCodecSupport) writer).setEnableLoggingRequestDetails(true));

        // --- Step 4: Assemble the WebClient ---
        return WebClient.builder()
                .baseUrl(plaidApiBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(exchangeStrategies)
                .filter(logRequest())   // DEBUG: log every outbound request URL
                .filter(logResponse())  // DEBUG: log every inbound response status
                .build();
    }

    /**
     * WebClient filter that logs outbound request method and URL at DEBUG level.
     *
     * <p>Filters are applied in the order they're added to the builder. This one runs
     * before the request is sent, so it captures the final URL after any path/query
     * variable substitution.
     *
     * <p>Logging is gated on {@code log.isDebugEnabled()} to avoid the string
     * formatting overhead in production where DEBUG is disabled.
     */
    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            if (log.isDebugEnabled()) {
                log.debug("Plaid request: {} {}", clientRequest.method(), clientRequest.url());
                clientRequest.headers().forEach((name, values) ->
                        values.forEach(value -> log.debug("  header: {}={}", name, value))
                );
            }
            return Mono.just(clientRequest);
        });
    }

    /**
     * WebClient filter that logs the HTTP status code of every inbound response at
     * DEBUG level. Runs after the response headers arrive but before the body is read.
     */
    private ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(clientResponse -> {
            if (log.isDebugEnabled()) {
                log.debug("Plaid response status: {}", clientResponse.statusCode());
            }
            return Mono.just(clientResponse);
        });
    }
}
