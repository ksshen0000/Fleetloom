-- Goods on a carrier (CAR-05): recorded for lookup, not used by dispatching in v1.

CREATE TABLE carrier_goods (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    carrier_code varchar(64)    NOT NULL REFERENCES carrier (code) ON DELETE CASCADE,
    line_no      integer        NOT NULL,
    sku          varchar(64)    NOT NULL,
    name         varchar(128),
    quantity     numeric(14, 3) NOT NULL CHECK (quantity >= 0),
    unit         varchar(16),
    batch_no     varchar(64),
    updated_at   timestamptz    NOT NULL,
    UNIQUE (carrier_code, line_no)
);

CREATE INDEX ix_carrier_goods_sku ON carrier_goods (sku);
