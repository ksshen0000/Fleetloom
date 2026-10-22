-- Transport orders (DSP-01..10) and per-vehicle dispatch settings (VEH-04)

CREATE TABLE transport_order (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_no          varchar(32) NOT NULL UNIQUE,
    external_no       varchar(64) UNIQUE,
    type              varchar(16) NOT NULL CHECK (type IN ('TRANSPORT', 'MOVE', 'CHARGE')),
    priority          integer     NOT NULL,
    carrier_code      varchar(64),
    carrier_type      varchar(64),
    source_location   varchar(64),
    target_location   varchar(64),
    map_code          varchar(64),
    requested_vehicle varchar(64),
    assigned_vehicle  varchar(64),
    status            varchar(16) NOT NULL,
    current_leg       integer     NOT NULL DEFAULT 0,
    -- legs of the order as a JSON array (domain record Leg)
    legs              jsonb       NOT NULL,
    reason            varchar(512),
    route_id          varchar(64),
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    finished_at       timestamptz,
    version           integer     NOT NULL DEFAULT 0
);
CREATE INDEX ix_transport_order_status ON transport_order (status, created_at DESC);

CREATE TABLE vehicle_dispatch_setting (
    vehicle_code varchar(64) PRIMARY KEY,
    accepting    boolean     NOT NULL,
    updated_at   timestamptz NOT NULL
);
