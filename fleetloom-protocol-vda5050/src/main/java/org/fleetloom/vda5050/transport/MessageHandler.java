package org.fleetloom.vda5050.transport;

@FunctionalInterface
public interface MessageHandler {

    void onMessage(String topic, byte[] payload);
}
