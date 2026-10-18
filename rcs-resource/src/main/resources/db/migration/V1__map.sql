-- Map management (requirements MAP-01..MAP-10)

CREATE TABLE rcs_map (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code              varchar(64)  NOT NULL UNIQUE,
    name              varchar(128) NOT NULL,
    description       varchar(512),
    active_version_no integer,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL
);

CREATE TABLE map_version (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    map_id       bigint      NOT NULL REFERENCES rcs_map (id) ON DELETE CASCADE,
    version_no   integer     NOT NULL,
    status       varchar(16) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    -- points, paths and areas of the version as one JSON document (domain record MapContent)
    content      jsonb       NOT NULL,
    revision     integer     NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    published_at timestamptz,
    published_by varchar(64),
    UNIQUE (map_id, version_no)
);

-- At most one draft and one published version per map
CREATE UNIQUE INDEX uk_map_version_one_draft ON map_version (map_id) WHERE status = 'DRAFT';
CREATE UNIQUE INDEX uk_map_version_one_published ON map_version (map_id) WHERE status = 'PUBLISHED';
