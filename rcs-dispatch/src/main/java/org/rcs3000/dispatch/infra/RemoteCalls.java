package org.rcs3000.dispatch.infra;

import java.util.function.Supplier;

import org.rcs3000.dispatch.core.RemoteCallException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Turns HTTP client failures into {@link RemoteCallException} carrying the remote error code. */
final class RemoteCalls {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RemoteCalls() {
    }

    static <T> T call(String service, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            String code = "HTTP_" + e.getStatusCode().value();
            String message = e.getMessage();
            try {
                JsonNode body = JSON.readTree(e.getResponseBodyAsString());
                code = body.path("code").asString(code);
                message = body.path("message").asString(message);
            } catch (RuntimeException ignored) {
                // not an ApiResponse body
            }
            throw new RemoteCallException(code, service + ": " + message, e);
        } catch (ResourceAccessException e) {
            throw new RemoteCallException("UNAVAILABLE", service + " is unreachable: " + e.getMessage(), e);
        }
    }

    static boolean isNotFound(RemoteCallException e) {
        return e.errorCode().endsWith("NOT_FOUND") || e.errorCode().equals("HTTP_404");
    }
}
