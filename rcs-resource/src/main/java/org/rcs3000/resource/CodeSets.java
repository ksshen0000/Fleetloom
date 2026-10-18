package org.rcs3000.resource;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Stores sets of codes as one comma-separated column. */
public final class CodeSets {

    private CodeSets() {
    }

    public static String join(Set<String> codes) {
        return new TreeSet<>(codes).stream().collect(Collectors.joining(","));
    }

    public static Set<String> split(String column) {
        if (column == null || column.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(column.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
