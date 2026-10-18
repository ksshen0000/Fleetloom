package org.rcs3000.resource.map.application;

import org.rcs3000.resource.map.domain.ValidationReport;

/**
 * Result of saving draft content: the new revision to send with the next save, and the validation
 * report so the editor can show problems immediately.
 */
public record SaveDraftResult(int revision, ValidationReport validation) {
}
