package org.rcs3000.contracts.storage;

import java.util.List;

/** Releases the locks {@code owner} holds on {@code locations}; others are left alone. */
public record ReleaseLocksDto(String owner, List<String> locations) {
}
