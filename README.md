# Lead Management SaaS

A multi-tenant backend for capturing and tracking sales leads: tenants sign up, push leads in over an API, move them through a configurable pipeline, and get notified as things change. Built as a modular monolith on Spring Modulith.

Java 21 · Spring Boot 3.4.3 · PostgreSQL 16 · Redis 7

> Not deployed. Runs locally with `docker compose up` plus `./mvnw spring-boot:run` — see [Running it](#running-it).

## Why a modular monolith

The obvious alternative was microservices, one per module. I didn't do that, and the reasoning is most of what this project is about.

**One transaction boundary.** Creating a lead writes the lead, an activity row, and an outbox event. In a single database that's one transaction that either commits or doesn't. Split across services, the same operation needs a saga and a compensating action for every partial failure — a large amount of machinery to solve a problem I'd have created myself.

**Module seams without deployment cost.** Spring Modulith enforces the boundaries at build time: a module can only be reached through its published API, and a violation fails the build rather than sliding in during review. So the seams that would let a module be extracted later are real and checked, but I run one process, one deploy, one log stream.

**Events, not direct calls.** Modules talk through domain events (`LeadCreatedEvent`, `LeadStageChangedEvent`, `TenantRegisteredEvent`). The lead module doesn't know notifications exist. That's what makes the seams hold — extraction later means changing the transport, not untangling call graphs.

The honest trade: this scales vertically, and a module with genuinely different scaling needs would have to be pulled out. Nothing here needs that yet.

## How multi-tenancy works

Row-level isolation, enforced by Hibernate rather than by remembering to add a `WHERE` clause.

```
JwtAuthenticationFilter  →  reads tenantId from the token claims
                            puts it in TenantContext (a ThreadLocal)
CurrentTenantResolver    →  hands that value to Hibernate
BaseEntity               →  @TenantId column on every entity
```

Hibernate then injects `tenant_id` on insert and filters every query automatically. A query that forgets the tenant isn't possible, because the application never writes the filter itself.

**The part that was actually hard.** A ThreadLocal works fine on a request thread and is empty everywhere else — which breaks the moment anything runs in the background. The outbox processor has to claim events across all tenants, so it runs under a `SYSTEM_TENANT_ID` sentinel for the claim query, then sets `TenantContext` per event before processing it. Same problem, different answer, in `LeadScoringService`: it listens synchronously rather than with `@Async`, because an async listener would run on a worker thread with no tenant context at all. Both are commented at the point where someone would otherwise "fix" them.

## What's in it

| Module | What it does |
|---|---|
| `lead` | Lead ingestion with JSONB custom fields, duplicate detection, activity trail, stage transitions |
| `pipeline` | Configurable stages per tenant; a default pipeline is created on tenant registration |
| `notification` | Listens for lead events, writes notifications, exposes a read/unread feed |
| `tenant` | Tenant registration and lifecycle |
| `user` | Registration, login, JWT issuing, roles |
| `infrastructure` | Security, outbox, idempotency, rate limiting, tenant resolution, correlation IDs |

**Transactional outbox.** Events are written in the same transaction as the domain change, then picked up by a background processor with retry and a `next_attempt_at` backoff column. Nothing is published that wasn't also committed.

**Idempotency.** `POST` endpoints accept an `X-Idempotency-Key` header. A repeat within 24 hours replays the original response rather than creating a second record. The filter caches the request body so the key can be claimed before the handler runs.

**Rate limiting.** Fixed window, 1000 requests/hour per tenant, on lead ingestion. Backed by Redis, enforced by a servlet filter after the tenant is known.

**Lead scoring in Redis.** A per-tenant sorted set gives ranked leads without a query. Postgres stays the source of truth — the ZSET is derived data, rebuildable at any time, and every Redis call fails open so an outage degrades ranking instead of breaking lead creation.

## API

```
POST   /api/auth/register          register a tenant and its first admin
POST   /api/auth/login             exchange credentials for a JWT

POST   /api/v1/leads               create a lead    (X-Idempotency-Key, rate limited)
PATCH  /api/v1/leads/{id}/stage    move a lead to another pipeline stage

GET    /api/v1/pipelines           list pipelines
GET    /api/v1/pipelines/{id}      one pipeline with its stages
POST   /api/v1/pipelines           create a pipeline

GET    /api/v1/notifications       notification feed
PATCH  /api/v1/notifications/{id}/read
```

Every route except `/api/auth/**` requires a bearer token. `postman-collection.json` in the repo root has the requests.

## Running it

```bash
docker compose up -d          # Postgres 16 + Redis 7
./mvnw spring-boot:run        # Flyway migrates on boot
```

Health and metrics at `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`.

Tests:

```bash
./mvnw test
```

Unit tests run standalone. The `*DockerComposeTest` classes need the compose stack up — they exercise tenant isolation, outbox processing, idempotency, stage changes, pipelines, and notification delivery against real Postgres and Redis.

## Stack

| | |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.4.3, Spring Modulith |
| Database | PostgreSQL 16, Hibernate 6 `@TenantId`, Flyway |
| Cache | Redis 7 (scoring, caching, rate-limit counters) |
| Auth | Spring Security, JJWT (HS256), BCrypt |
| Build | Maven wrapper, Docker Compose |

## What isn't here

Not deployed anywhere, so there's no live URL. No load testing yet, which is why this README quotes no throughput or latency figures — the ones worth quoting are the ones measured on stated hardware, and I haven't run that. Notification delivery is in-app only; there's no email or webhook transport.
