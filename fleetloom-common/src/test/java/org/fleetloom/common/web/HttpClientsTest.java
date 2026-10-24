package org.fleetloom.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class HttpClientsTest {

    @Test
    void aResponseThatNeverComesTimesOut() throws IOException {
        HttpServer silent = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        silent.createContext("/", exchange -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        silent.start();
        try {
            RestClient client = HttpClients.withTimeouts(RestClient.builder(), "http://localhost:" + silent.getAddress().getPort(),
                    Duration.ofSeconds(1), Duration.ofMillis(300)).build();
            long started = System.nanoTime();
            assertThatThrownBy(() -> client.post().uri("/lock").body("{}").retrieve().toBodilessEntity())
                    .isInstanceOf(ResourceAccessException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        } finally {
            silent.stop(0);
        }
    }
}
