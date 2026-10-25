# telemed-ia-document-generation-api

> document-generation bounded context: service API (`document-service`)

Part of the **TeleMed IA** distributed system. Governance, requirements and contracts live in
[`telemed-ia-docs`](https://github.com/code-corhuila/telemed-ia-docs).

## Purpose

Generates consultation PDF documents from valid post-consultation summaries and gives each
patient authorized access to their own documents (HU-016).

| Item | Value |
|---|---|
| Public contract | `telemed-ia-docs/07-api/contracts/openapi/document-service.yaml` |
| Database | schema `document_generation`, owned by `telemed-ia-document-generation-db` |
| Database user | `document_generation_app` (never the administrator, Anexo J.7) |
| Consumes / publishes | `PostConsultationSummaryGenerated` / `ConsultationPdfGenerated` |
| Binary storage | S3-compatible object storage, RustFS (ADR-015, ADR-021) |

This repository contains **no migrations**: the schema lives only in the `-db` repository
(numeral 5.2.1).

Every `ConsultationPdfGenerated` is written to `document_generation.outbox_event` in the
transaction that marks the document `AVAILABLE`, and a relay of the service publishes it to
RabbitMQ every 5 seconds (numeral 5.3.11, DEC-008): queue `consultation_pdf_generated`,
`message_id` = event id, the ADR-011 envelope as body, published only after the broker
confirms it. Without the broker the service keeps working and the events wait in the outbox.

## Architecture

Hexagonal, in three Maven modules (Anexo C):

| Module | Contains | Depends on |
|---|---|---|
| `document-generation-core` | domain model, ports and use cases | nothing: the build fails if a framework is added |
| `document-generation-adapters` | HTTP, persistence, messaging and storage adapters | core |
| `document-generation-app` | composition root and every explicit limit (`application.yml`) | core, adapters |

## Requirements

- JDK 21 and Maven 3.9+ (Anexo I). The build fails with any other JDK.
- Docker, to run the service inside the platform.

## Build and test

```bash
mvn -B verify
```

## Run

Inside the platform, from `telemed-ia-infra-postgres` (this file is included there):

```bash
docker compose --env-file env/dev.env up -d document-generation-api
```

Locally, without Docker:

```bash
mvn -B -pl document-generation-app -am package
java -jar document-generation-app/target/document-generation-api.jar
curl http://localhost:8080/health
```

Every variable the service reads is listed in `.env.example`.

## Branching

Three permanent branches. **None of them accepts a direct commit** — you enter through a child
branch and leave through a Pull Request.

```
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  qa/...
main     <--PR--  release/...  hotfix/...
```

Promotion happens **by re-application** (`git cherry-pick -x`), never by merging one permanent
branch into another: `merge develop -> qa` and `merge qa -> main` do not exist in this model.

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule (`00-governance/git-conventions.md` in `telemed-ia-docs`).

Full policy: `00-governance/branching-policy.md` in `telemed-ia-docs`.
