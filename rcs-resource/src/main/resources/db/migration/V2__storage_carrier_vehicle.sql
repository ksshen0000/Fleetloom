-- Storage objects (STO), carriers (CAR) and the vehicle registry (VEH-01/02).
-- Cross-object references use business codes, not ids (docs/requirements.md 14.3).
-- Sets of codes are stored comma-separated.

CREATE TABLE storage_zone (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        varchar(64)  NOT NULL UNIQUE,
    name        varchar(128) NOT NULL,
    type        varchar(32)  NOT NULL,
    description varchar(512),
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL
);

CREATE TABLE storage_location (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                  varchar(64)  NOT NULL UNIQUE,
    zone_code             varchar(64)  NOT NULL REFERENCES storage_zone (code),
    type                  varchar(16)  NOT NULL CHECK (type IN ('GROUND', 'RACK', 'STATION', 'BUFFER')),
    map_code              varchar(64)  NOT NULL,
    access_point          varchar(64)  NOT NULL,
    access_theta          double precision,
    level                 integer      NOT NULL DEFAULT 0,
    height                double precision NOT NULL DEFAULT 0,
    allowed_carrier_types varchar(512) NOT NULL DEFAULT '',
    status                varchar(16)  NOT NULL
        CHECK (status IN ('EMPTY', 'OCCUPIED', 'LOCKED_IN', 'LOCKED_OUT', 'DISABLED')),
    -- status the location returns to when re-enabled
    status_before_disable varchar(16),
    lock_owner            varchar(64),
    carrier_code          varchar(64),
    version               integer      NOT NULL DEFAULT 0,
    created_at            timestamptz  NOT NULL,
    updated_at            timestamptz  NOT NULL
);
CREATE INDEX ix_storage_location_zone ON storage_location (zone_code, status);
CREATE UNIQUE INDEX uk_storage_location_carrier ON storage_location (carrier_code) WHERE carrier_code IS NOT NULL;

CREATE TABLE carrier_type (
    id                       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                     varchar(64)  NOT NULL UNIQUE,
    name                     varchar(128) NOT NULL,
    category                 varchar(16)  NOT NULL CHECK (category IN ('PALLET', 'SHELF', 'CART', 'BIN')),
    length                   double precision NOT NULL,
    width                    double precision NOT NULL,
    height                   double precision NOT NULL,
    max_load                 double precision NOT NULL,
    compatible_vehicle_types varchar(512) NOT NULL DEFAULT '',
    created_at               timestamptz  NOT NULL,
    updated_at               timestamptz  NOT NULL
);

CREATE TABLE carrier (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code          varchar(64) NOT NULL UNIQUE,
    type_code     varchar(64) NOT NULL REFERENCES carrier_type (code),
    status        varchar(16) NOT NULL CHECK (status IN ('IDLE', 'IN_TRANSIT', 'DISABLED')),
    location_type varchar(16) NOT NULL CHECK (location_type IN ('STORAGE', 'VEHICLE', 'NONE')),
    location_ref  varchar(64),
    last_moved_at timestamptz,
    version       integer     NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL
);

CREATE TABLE carrier_movement (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    carrier_code varchar(64) NOT NULL,
    from_type    varchar(16) NOT NULL,
    from_ref     varchar(64),
    to_type      varchar(16) NOT NULL,
    to_ref       varchar(64),
    order_ref    varchar(64),
    moved_at     timestamptz NOT NULL
);
CREATE INDEX ix_carrier_movement_carrier ON carrier_movement (carrier_code, moved_at DESC);

CREATE TABLE vehicle_type (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code              varchar(64)  NOT NULL UNIQUE,
    name              varchar(128) NOT NULL,
    category          varchar(16)  NOT NULL CHECK (category IN ('JACKING', 'FORKLIFT', 'ROLLER', 'TOWING')),
    length            double precision NOT NULL,
    width             double precision NOT NULL,
    max_speed         double precision NOT NULL,
    max_load          double precision NOT NULL,
    supported_actions varchar(512) NOT NULL DEFAULT '',
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL
);

CREATE TABLE vehicle (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                  varchar(64)  NOT NULL UNIQUE,
    name                  varchar(128) NOT NULL,
    type_code             varchar(64)  NOT NULL REFERENCES vehicle_type (code),
    manufacturer          varchar(64)  NOT NULL,
    serial_number         varchar(64)  NOT NULL,
    simulated             boolean      NOT NULL DEFAULT false,
    enabled               boolean      NOT NULL DEFAULT true,
    initial_point         varchar(64),
    charge_threshold_low  double precision NOT NULL DEFAULT 20,
    charge_threshold_high double precision NOT NULL DEFAULT 90,
    created_at            timestamptz  NOT NULL,
    updated_at            timestamptz  NOT NULL,
    UNIQUE (manufacturer, serial_number)
);
