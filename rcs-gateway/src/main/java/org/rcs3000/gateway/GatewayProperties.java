package org.rcs3000.gateway;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param services base URL per service; {@code /api/<name>/**} is routed to {@code services.<name>}
 * @param push     event types forwarded to browsers over {@code /ws}
 */
@ConfigurationProperties("rcs.gateway")
public record GatewayProperties(Map<String, URI> services, Push push) {

    public record Push(List<String> events) {
    }
}
