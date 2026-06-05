# Luke Capability Engine

Spring Boot service that owns the platform's **capabilities, subscriptions,
calendars and SLA** domain. It runs on port **8082** and is reached by the UI
through `luke-core-engine`'s `CapabilitiesProxyController`
(`/api/capabilities/**`, `/api/my-subscriptions/**`, `/api/business-calendars/**`,
`/api/sla/**`, `/api/process-calendars/**`).

## Database — shared instance, isolated schema

The engine uses the **same managed Postgres** as `luke-core-engine`
(Render database `luke-camunda-db`, database name `luke_camunda`), but keeps all
of its tables in a **dedicated schema** so it never collides with the
Camunda/core-engine tables.

- Schema name: `DB_SCHEMA` (default `capability`).
- Hibernate is configured with `default_schema=${DB_SCHEMA}` and
  `hbm2ddl.create_namespaces=true`, so the schema is created automatically
  (`CREATE SCHEMA IF NOT EXISTS …`) on first start, and `ddl-auto=update`
  creates/updates the tables inside it.

## Run locally

Default profile uses an H2 file DB (zero setup):

```bash
./mvnw spring-boot:run
# → http://localhost:8082/actuator/health
# → http://localhost:8082/api/capabilities
```

Against the shared Postgres:

```bash
SPRING_PROFILES_ACTIVE=postgres \
POSTGRES_HOST=localhost POSTGRES_PORT=5432 POSTGRES_DATABASE=luke_camunda \
DB_USERNAME=luke DB_PASSWORD=luke DB_SCHEMA=capability \
./mvnw spring-boot:run
```

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET    | `/api/capabilities` | List the capability catalog |
| GET    | `/api/capabilities/{code}` | Get one capability |
| POST   | `/api/capabilities` | Create a capability |
| DELETE | `/api/capabilities/{code}` | Delete a capability |
| GET    | `/api/my-subscriptions` | Capabilities active for the caller's tenant |
| GET    | `/actuator/health` | Health check (Render) |

The catalog is seeded with `CALENDAR` and `SLA` on first start.

## Deploy

`render.yaml` defines the web service and wires the Postgres connection from the
existing `luke-camunda-db`. Push to `main` (or run `./build.sh`) to trigger a
Render build & deploy.
