-- Users of the console (requirement WEB-01, WEB-09). Passwords are BCrypt hashes.

CREATE TABLE rcs_user (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      varchar(64)  NOT NULL UNIQUE,
    display_name  varchar(128) NOT NULL,
    password_hash varchar(100) NOT NULL,
    role          varchar(16)  NOT NULL CHECK (role IN ('ADMIN', 'OPERATOR', 'VIEWER')),
    enabled       boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL
);
