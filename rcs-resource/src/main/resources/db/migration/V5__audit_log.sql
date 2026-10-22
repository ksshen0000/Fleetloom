-- Operation audit log (WEB-08): every changing request through the gateway, kept for rcs.audit.retention.

CREATE TABLE rcs_audit_log (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at          timestamptz   NOT NULL,
    username    varchar(64),
    role        varchar(16),
    method      varchar(10)   NOT NULL,
    path        varchar(500)  NOT NULL,
    query       varchar(1000),
    status      integer       NOT NULL,
    duration_ms bigint        NOT NULL,
    client_ip   varchar(64)
);

CREATE INDEX rcs_audit_log_at ON rcs_audit_log (at DESC);
CREATE INDEX rcs_audit_log_user_at ON rcs_audit_log (username, at DESC);
