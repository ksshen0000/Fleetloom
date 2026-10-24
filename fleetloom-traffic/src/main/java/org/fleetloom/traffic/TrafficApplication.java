package org.fleetloom.traffic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TrafficApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrafficApplication.class, args);
    }
}
