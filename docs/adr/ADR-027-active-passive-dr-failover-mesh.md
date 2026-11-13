# ADR-027: Multi-Region Active-Passive Disaster Recovery & Cross-Region Idempotency

## Status
Accepted & Verified

## Context
Mission-critical LEO/GEO satellite telemetry cannot tolerate prolonged downtime or duplicate ERP postings if the primary SAP BTP region in Frankfurt (`eu10`) suffers a catastrophic infrastructure outage.

## Decision
1. **Anycast GSLB Routing:** Route ground station telemetry through a BGP Anycast Edge layer with 5-second health probes, promoting AWS Virginia (`us10`) automatically upon `HTTP 503` or timeout.
2. **Asynchronous AEM DMR Bridge:** Replicate `aerospace/telemetry/v1/>` events from `eu10` to `us10` asynchronously to preserve `<15ms` baseline ingestion latency.
3. **Global Redis SHA-256 Deduplication:** Enforce atomic `SETNX` idempotency checks inside SAP CPI (`IdempotentTelemetryProcessor`) so frames committed in `eu10` during the final 1.2s before outage are cleanly suppressed during `us10` replay.
4. **Asymmetric Failback:** Automate failover (`EU10 -> US10`) within `<30s`, but mandate manual SRE failback (`US10 -> EU10`) to prevent split-brain flapping.
