package org.fleetloom.common.web;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Service-to-service HTTP clients. Every call has a deadline: a lost response must never block a
 * caller for good, least of all the single-threaded dispatcher or traffic controller.
 */
public final class HttpClients {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private HttpClients() {
    }

    /** A copy of {@code builder} for {@code baseUrl} whose calls give up after the default timeouts. */
    public static RestClient.Builder withTimeouts(RestClient.Builder builder, String baseUrl) {
        return withTimeouts(builder, baseUrl, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    public static RestClient.Builder withTimeouts(RestClient.Builder builder, String baseUrl, Duration connect, Duration read) {
        // HTTP/1.1: the JDK client's default h2c upgrade on plain HTTP is a known source of stalls with bodies
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connect).build());
        requests.setReadTimeout(read);
        return builder.clone().baseUrl(baseUrl).requestFactory(requests);
    }
}
