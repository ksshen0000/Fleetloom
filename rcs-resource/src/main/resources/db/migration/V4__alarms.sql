-- Platform alarms (requirements VEH-08, DSP-10, WEB-08). One open alarm per condition key; a condition
-- that comes back after being cleared starts a new row, so the table is also the alarm history.

CREATE TABLE rcs_alarm (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alarm_key       varchar(200)  NOT NULL,
    category        varchar(32)   NOT NULL,
    level           varchar(16)   NOT NULL CHECK (level IN ('WARNING', 'ERROR', 'FATAL')),
    vehicle_code    varchar(64),
    order_no        varchar(64),
    message         varchar(1000) NOT NULL,
    status          varchar(16)   NOT NULL CHECK (status IN ('ACTIVE', 'ACKNOWLEDGED', 'CLEARED')),
    occurrences     integer       NOT NULL,
    raised_at       timestamptz   NOT NULL,
    last_seen_at    timestamptz   NOT NULL,
    acknowledged_by varchar(64),
    acknowledged_at timestamptz,
    cleared_by      varchar(64),
    cleared_at      timestamptz
);

CREATE UNIQUE INDEX rcs_alarm_open_key ON rcs_alarm (alarm_key) WHERE status <> 'CLEARED';
CREATE INDEX rcs_alarm_raised_at ON rcs_alarm (raised_at DESC);
