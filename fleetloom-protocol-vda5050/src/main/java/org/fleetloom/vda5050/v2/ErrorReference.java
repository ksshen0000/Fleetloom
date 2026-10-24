package org.fleetloom.vda5050.v2;

/** Points an error at what caused it, e.g. {@code headerId}, {@code orderId}, {@code nodeId}. */
public record ErrorReference(String referenceKey, String referenceValue) {
}
