# Compliance Audit Logging Runbook (DPDP/GDPR)

**Owner:** Platform / Compliance  
**Scope:** Immutable, tamper-evident application audit trail
(`com.bhukkad.common.audit.AuditEvent`, hash-chained) shipped asynchronously to
Kafka topic `audit.events` and archived to S3 (see
`k8s/observability/audit-events-archival.md`).

## 1. How the audit trail works

- Each `AuditEvent` records `actorId`, `action`
  (`CREATE|READ|UPDATE|DELETE|EXPORT|CONSENT_GIVEN`), `resourceType`,
  `resourceId`, JSON `before`/`after`, `correlationId`, `decision`
  (`ALLOW|DENY`), and `timestamp`.
- **Hash chain:** every record stores
  - `prevHash` — `currentHash` of the previous record (or the genesis
    `000…000` for the first record), and
  - `currentHash = SHA-256(prevHash + timestamp + actorId + action +
    resourceType + resourceId + before + after + correlationId + decision)`.
- **Outbox:** the audit row and an outbox row are written in the same business
  transaction (`AuditEventService.record`), so the audit record never outlives
  a rolled-back change and the Kafka publish is asynchronous/non-blocking. The
  outbox relay routes `AUDIT_EVENT` rows to the `audit.events` topic.

### Hash chain algorithm (reference)

```
prevHash_0      = 0000000000000000000000000000000000000000000000000000000000000000  (genesis)
currentHash_i   = SHA-256( concat(prevHash_i, timestamp_i, actorId_i, action_i,
                          resourceType_i, resourceId_i, before_i, after_i,
                          correlationId_i, decision_i) )
prevHash_{i+1}  = currentHash_i
```

Concatenation joins the string values in that exact order; `null` fields are
treated as empty strings. Hash is rendered as lowercase hex (64 chars).

## 2. Verify audit integrity

Use the platform utility
`com.bhukkad.common.audit.AuditChainVerifier#verifyChain(List<AuditEvent>)`,
which recomputes each record's hash and validates the `prevHash` linkage. It
returns the zero-based index of the first inconsistent record, or `-1` when the
chain is intact.

### Ad-hoc verification (application / operator)

1. Fetch the ordered chain for the scope under review (oldest first), e.g. by
   `resourceType/resourceId` or a time window, from the service database or the
   archived Parquet in S3 (Athena).
2. Run `AuditChainVerifier.verifyChain(events)`.
   - `-1` → chain intact for the fetched sequence.
   - `>= 0` → the record at that index is the first inconsistency (its
     `currentHash` does not match recomputation, or its `prevHash` does not
     match the predecessor's `currentHash`).

### Example (Java)

```java
List<AuditEvent> events = auditEventRepository.findAll(/* ordered oldest first */);
int bad = AuditChainVerifier.verifyChain(events);
if (bad == -1) {
    // chain intact
} else {
    // events.get(bad) is the first tampered/broken record
}
```

### Unit-test proof

`com.bhukkad.common.audit.AuditEventServiceTest` proves: recording `N` events
produces a valid chain (`verifyChain` returns `-1`), and tampering with a
middle event (mutating a field or severing a `prevHash` link) makes
`verifyChain` return the tampered index.

## 3. Respond to tamper detection

Treat any `verifyChain` result `>= 0` as a **security incident** (potential
unauthorized modification of audit evidence).

1. **Contain:** restrict DB write access for the affected service/database;
   snapshot the table and relevant WAL/binlog segments; preserve the S3/Parquet
   archive partition (Object Lock should prevent mutation) for comparison.
2. **Assess scope:** identify the first inconsistent index and compare the live
   DB rows against the immutable S3 archive and application logs/trace ids to
   determine what changed and when.
3. **Notify:** escalate to Security/Compliance and the DPO per the incident
   response policy; DPDP/GDPR may require breach assessment/notification
   depending on impact.
4. **Recover evidence:** treat the S3 Object-Locked archive (and any pg_audit /
   DB-audit logs, if enabled in production) as the source of truth; do **not**
   "repair" hashes in place (rewriting breaks evidentiary value).
5. **Remediate:** rotate credentials/keys if compromise is suspected, fix the
   access-control gap, and document root cause and corrective actions.
6. **Harden (production ops):** enable `pg_audit` at the database layer for
   DDL/DML audit when the extension is confirmed available in the target
   environment (no application runtime dependency added here); enforce S3
   Object Lock COMPLIANCE retention and least-privilege IAM on the archive.

## 4. Compliance API (admin)

Admin-only endpoints (require existing admin authorization):

- `GET /api/v1/compliance/audit-logs` — search audit logs. Filters:
  `actorId`, `resourceType`, `resourceId`, `action`, `from`, `to` (ISO-8601
  date-time range on `createdAt`).
- `GET /api/v1/compliance/audit-logs/export` — export the filtered set as
  **JSON** (default, `format=json`) or **CSV** (`format=csv`). Response
  includes `X-Export-Signature` (HMAC-SHA256 over the body) and
  `X-Export-Signature-Algorithm: HmacSHA256` for integrity verification of the
  export artifact.

## 5. Operational checks

- Outbox health: audit rows enqueue as `PENDING` and the relay publishes to
  `audit.events`; monitor outbox lag/DLQ metrics and the
  `AUDIT_RECORDED`/`OUTBOX_*` logs.
- Archive freshness: monitor Firehose `DeliveryToS3.DataFreshness` and confirm
  daily partitions land in S3.
- Periodic integrity sampling: run `verifyChain` over recent partitions and
  alert on any non-`-1` result.
