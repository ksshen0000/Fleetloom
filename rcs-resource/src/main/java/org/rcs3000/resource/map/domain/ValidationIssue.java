package org.rcs3000.resource.map.domain;

/**
 * @param rule        stable rule identifier, e.g. {@code PATH_UNKNOWN_POINT}
 * @param elementCode code of the offending point, path or area; {@code null} for map-level issues
 */
public record ValidationIssue(Severity severity, String rule, String elementCode, String message) {

    public enum Severity {
        /** Blocks publishing. */
        ERROR,
        /** Reported, but does not block publishing. */
        WARNING
    }

    static ValidationIssue error(String rule, String elementCode, String message) {
        return new ValidationIssue(Severity.ERROR, rule, elementCode, message);
    }

    static ValidationIssue warning(String rule, String elementCode, String message) {
        return new ValidationIssue(Severity.WARNING, rule, elementCode, message);
    }
}
