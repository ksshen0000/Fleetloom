-- One schema and one login per service: a service can only touch its own data
-- (docs/tech-evaluation.md section 5.1).

CREATE ROLE fleetloom_resource LOGIN PASSWORD 'fleetloom_resource';
CREATE SCHEMA resource AUTHORIZATION fleetloom_resource;

CREATE ROLE fleetloom_dispatch LOGIN PASSWORD 'fleetloom_dispatch';
CREATE SCHEMA dispatch AUTHORIZATION fleetloom_dispatch;

CREATE ROLE fleetloom_fleet LOGIN PASSWORD 'fleetloom_fleet';
CREATE SCHEMA fleet AUTHORIZATION fleetloom_fleet;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
