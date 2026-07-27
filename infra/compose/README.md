# Local data plane

```bash
cp .env.example .env   # set real passwords
docker compose up -d
```

## Port map

Host ports are offset from defaults so this stack coexists with other local projects:

| Service | Host port | Container default | Notes |
|---|---|---|---|
| Postgres | **5442** | 5432 | user/db `khukuri` |
| Redis | **6389** | 6379 | |
| ClickHouse HTTP | **8143** | 8123 | `curl localhost:8143/ping` |
| ClickHouse native | **9010** | 9000 | |
| Kafka (external) | **19092** | 9092 | in-network clients use `kafka:9092` |
| OTLP gRPC / HTTP | **4317 / 4318** | standard | point your app's OTel exporter here |

Application services (gateway, identity, …) will reserve **8181–8189** as they join; 8080 is intentionally never used.

## Smoke test

```bash
docker compose ps                          # everything healthy
curl -s localhost:8143/ping                # ClickHouse: "Ok."
curl -s -X POST localhost:4318/v1/logs \
  -H 'Content-Type: application/json' -d '{"resourceLogs":[]}'   # {} = collector accepting OTLP
docker compose logs otel-collector --tail 5
```
