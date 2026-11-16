# Audit Events → S3 Archival (Kinesis Firehose-style) — Deployment Sketch

> **Not actual AWS infrastructure.** This is a deployment diagram and notes
> describing how the `audit.events` Kafka topic is archived to S3 for
> DPDP/GDPR-compliant long-term, tamper-evident retention. Provisioning is a
> production ops step (Terraform/CloudFormation) once the target account and
> MSK/Kinesis connectivity are confirmed.

## Data flow

```mermaid
flowchart LR
    subgraph Services
        A[Business code] -->|record() in tx| B[(per-service DB\nAuditEvent + Outbox row)]
        B -->|OutboxPollPublisher| C[Kafka topic\naudit.events]
    end

    subgraph Archival
        C --> D[Amazon Kinesis Data Firehose\nKafka source / MSK connector]
        D -->|buffer 60s or 128MB| E[Format conversion\nJSON → Parquet\nGlue Schema Registry]
        E --> F[(Amazon S3\naudit-archive bucket)]
        F --> G[S3 Object Lock\nCOMPLIANCE mode\nWORM retention]
    end

    subgraph Analytics / Audit
        F --> H[AWS Glue Data Catalog]
        H --> I[Amazon Athena\nad-hoc compliance queries]
    end
```

## S3 layout (partitioned by event date)

Objects are delivered with a dynamic partition prefix derived from the event
timestamp so queries and lifecycle rules operate on date partitions:

```
s3://bhukkad-audit-archive/
  env=prod/
    year=2026/
      month=11/
        day=16/
          audit-events-1-2026-11-16-02-30-00-<guid>.parquet
```

Firehose dynamic partitioning keys (extracted from the record):
- `year`, `month`, `day` from `timestamp` (or `createdAt`).

## Parquet / schema

- **Format:** Apache Parquet, Snappy compression.
- **Schema:** mirrors the `AuditEvent` envelope shipped on `audit.events`
  (`id`, `timestamp`, `actorId`, `action`, `resourceType`, `resourceId`,
  `before`, `after`, `correlationId`, `decision`, `prevHash`, `currentHash`,
  `createdAt`). Schema versioned in AWS Glue Schema Registry; the envelope is
  additive-only.
- **Glue catalog:** table `audit_events` partitioned by `year/month/day` for
  Athena.

## Immutability & retention

- **S3 Object Lock (COMPLIANCE mode):** prevents deletion/overwrite for the
  regulatory retention period (e.g. 7 years, aligned to DPDP/GDPR policy).
- **Versioning:** enabled on the bucket.
- **SSE-KMS:** server-side encryption with a dedicated CMK; key policy grants
  decrypt only to the compliance role.
- **Access:** bucket policy denies non-TLS and denies writes outside Firehose
  delivery role; reads restricted to the compliance/audit IAM role.

## Kafka / Firehose configuration notes

- **Source:** `audit.events` topic (produced by the per-service outbox relay
  from `AuditEventService` outbox rows).
- **Consumer group:** dedicated `audit-firehose-archiver` group; offset
  commits only on successful S3 delivery (Firehose delivery guarantees).
- **Buffering:** size 128 MB or interval 60 s (whichever first) to balance
  latency and Parquet row-group efficiency.
- **Error output:** failed/undeserializable records delivered to
  `s3://bhukkad-audit-archive/errors/!{firehose:error-output-type}/...` for
  triage.
- **Throughput:** sized from `audit.events` partition count; scale Firehose
  and topic partitions together.

## Production ops steps (to be executed, not part of this change)

1. Confirm the `audit.events` Kafka topic exists and ACLs allow the Firehose
   consumer principal.
2. Provision the S3 bucket (Object Lock, versioning, SSE-KMS, bucket policy).
3. Create the Glue database/table and Schema Registry schema.
4. Deploy the Firehose delivery stream (Kafka/MSK source → Parquet → S3) with
   the partitioning and error prefixes above.
5. Validate end-to-end: record an audit event, observe it on `audit.events`,
   confirm Parquet object lands in the correct date partition, and query via
   Athena.
6. Enable CloudWatch alarms on `DeliveryToS3.DataFreshness` and
   `DeliveryToS3.Records`.
7. (Optional, ops) enable `pg_audit` at the database layer for DDL/DML audit to
   supplement application-level audit; do **not** add a runtime dependency on
   the extension in application code until the extension is confirmed available
   in the target environment.
