-- Vehicle status history (VEH-09): sampled positions and battery levels, kept for rcs.vehicle-history.retention.

CREATE TABLE rcs_vehicle_history (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vehicle_code varchar(64)      NOT NULL,
    at           timestamptz      NOT NULL,
    map_code     varchar(64),
    x            double precision NOT NULL,
    y            double precision NOT NULL,
    theta        double precision NOT NULL,
    battery      double precision NOT NULL,
    charging     boolean          NOT NULL,
    driving      boolean          NOT NULL,
    last_node_id varchar(64),
    connection   varchar(32)      NOT NULL
);

CREATE INDEX rcs_vehicle_history_vehicle_at ON rcs_vehicle_history (vehicle_code, at);
CREATE INDEX rcs_vehicle_history_at ON rcs_vehicle_history (at);
