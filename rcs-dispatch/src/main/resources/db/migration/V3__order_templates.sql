-- Order templates (DSP-03): which actions a transport does at pick-up and drop-off, and which points
-- it passes in between.

CREATE TABLE order_template (
    code        varchar(32)  PRIMARY KEY,
    name        varchar(64)  NOT NULL,
    pick_action varchar(64),
    drop_action varchar(64),
    via_points  jsonb        NOT NULL DEFAULT '[]',
    via_action  varchar(64),
    description varchar(256)
);

INSERT INTO order_template (code, name, pick_action, drop_action, description) VALUES
    ('STANDARD', '标准取放', 'pick', 'drop', '到源库位取货，到目标库位放货'),
    ('LIFT', '顶升搬运', 'lift', 'lower', '潜伏顶升车：到源点顶升，到目标点下降');
