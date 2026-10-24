package org.fleetloom.resource.map.domain;

import java.util.List;

public record ValidationReport(List<ValidationIssue> issues) {

    public ValidationReport {
        issues = List.copyOf(issues);
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.severity() == ValidationIssue.Severity.ERROR);
    }

    public List<ValidationIssue> errors() {
        return issues.stream().filter(i -> i.severity() == ValidationIssue.Severity.ERROR).toList();
    }

    public List<ValidationIssue> warnings() {
        return issues.stream().filter(i -> i.severity() == ValidationIssue.Severity.WARNING).toList();
    }
}
