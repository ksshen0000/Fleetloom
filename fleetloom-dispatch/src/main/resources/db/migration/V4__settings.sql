-- Dispatch parameters changed at runtime in the console (WEB-09); they override the configuration.

CREATE TABLE dispatch_setting (
    name       varchar(64)  PRIMARY KEY,
    value      varchar(256) NOT NULL,
    updated_at timestamptz  NOT NULL
);
