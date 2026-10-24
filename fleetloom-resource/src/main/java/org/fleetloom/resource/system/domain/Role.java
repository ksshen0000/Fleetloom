package org.fleetloom.resource.system.domain;

/** What a user may do; enforced by the gateway (docs/tech-evaluation.md section 7). */
public enum Role {
    /** Everything, including user management. */
    ADMIN,
    /** Operate the system: orders, vehicle commands, maps, storage. */
    OPERATOR,
    /** Read only. */
    VIEWER
}
