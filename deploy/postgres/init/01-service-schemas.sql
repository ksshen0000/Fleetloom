-- One schema and one login per service: a service can only touch its own data
-- (docs/tech-evaluation.md section 5.1).

CREATE ROLE rcs_resource LOGIN PASSWORD 'rcs_resource';
CREATE SCHEMA resource AUTHORIZATION rcs_resource;

CREATE ROLE rcs_dispatch LOGIN PASSWORD 'rcs_dispatch';
CREATE SCHEMA dispatch AUTHORIZATION rcs_dispatch;

CREATE ROLE rcs_fleet LOGIN PASSWORD 'rcs_fleet';
CREATE SCHEMA fleet AUTHORIZATION rcs_fleet;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
