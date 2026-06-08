# Test rig: SAP connector in a real midPoint server (Docker Compose)

Spins up a midPoint server backed by PostgreSQL 17 so the SAP ConnId connector can be
deployed and exercised through the midPoint GUI/REST. Adapted from the midpoint-lcm
rig; PostgreSQL bumped to 17.

## Quick start

```bash
# from the project root
docker compose -f docker/docker-compose.yml up -d      # first start (DB init + repo init + server)
docker/deploy-connector.sh                             # mvn package + deploy connector (+ JCo) + restart
```

- GUI: http://localhost:11080/midpoint — `administrator` / `T3stPw890uio`
- Logs: `docker compose -f docker/docker-compose.yml logs -f mp_server`
- DB:   `docker compose -f docker/docker-compose.yml exec mp_data psql -U midpoint` (password `db.secret.pw.007`)
- Tear down: `docker compose -f docker/docker-compose.yml down -v` (`-v` wipes volumes)

The GUI host port is **MP_PORT** (default 11080) and PostgreSQL is intentionally **not**
published on the host, so this rig can run alongside other midPoint/Postgres test stacks
without port clashes.

Versions and the GUI port are pinned in `docker/.env`: `MP_VER` (midPoint image tag),
`PG_VER` (PostgreSQL major version), `MP_PORT` (GUI host port).

## SAP JCo (required for the connector to load)

The connector bundle depends on SAP JCo, which is licensed and not redistributable, so
it is not part of the image or this repo. To use the connector you must supply the
**Linux** JCo build matching the container architecture and drop it into `docker/jco/`:

```
docker/jco/sapjco3.jar
docker/jco/libsapjco3.so
```

`deploy-connector.sh` copies the jar onto the classpath (`$MIDPOINT_HOME/lib`) and the
native library where `LD_LIBRARY_PATH` points (`$MIDPOINT_HOME/lib`). These files are
git-ignored. Without them midPoint still starts, but the SAP connector cannot be
instantiated (it would fail with a JCo `ClassNotFound`/`UnsatisfiedLinkError`).

Note: the macOS JCo (`sapjco3-darwinarm64-*`) used for `mvn test` does **not** work in the
Linux container — download the matching Linux build (e.g. `sapjco3-linuxx86_64-*` or
`-linuxaarch64-*` depending on the Docker VM architecture).

## How deployment works

Connector bundles and JCo are not bind-mounted (single-file/sub-dir bind mounts on
Colima/Docker Desktop on macOS are unreliable). `deploy-connector.sh` instead builds the
bundle, `docker cp`s it into `$MIDPOINT_HOME/icf-connectors/` (and JCo into
`$MIDPOINT_HOME/lib/`), and restarts `mp_server`. After a restart, create/refresh
the SAP resource in midPoint to pick up the connector.
