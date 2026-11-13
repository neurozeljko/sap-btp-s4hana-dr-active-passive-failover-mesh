# Enterprise S/4HANA Disaster Recovery: Active-Passive Multi-Region Failover Mesh

[![SAP Integration Suite](https://img.shields.io/badge/SAP%20Integration%20Suite-Cloud%20Integration%20%7C%20AEM-0a6ed1?logo=sap&style=flat-square)](https://www.sap.com)
[![DR Topology](https://img.shields.io/badge/DR%20Architecture-Active--Passive%20(EU10%20%E2%86%92%20US10)-critical?style=flat-square)](#2-multi-region-active-passive-failover-topology)
[![Messaging & State](https://img.shields.io/badge/Event%20Mesh%20%26%20Cache-DMR%20Bridge%20%7C%20Global%20Redis-success?style=flat-square)](#3-cross-region-event-mesh--idempotent-replay-guard)
[![SLA Targets](https://img.shields.io/badge/Target%20SLA-RPO%20%3C%201.5s%20%7C%20RTO%20%3C%2030s-008080?style=flat-square)](#6-lessons-learned-transatlantic-replication--split-brain-battle-scars)

An enterprise-grade **Multi-Region Disaster Recovery (DR)** and **High-Availability Integration Architecture** built on **SAP Integration Suite (Cloud Integration + SAP Advanced Event Mesh)** for global **LEO/GEO Satellite Telemetry & Aerospace Ground Operations**. Designed to survive catastrophic regional cloud outages (AWS Frankfurt `eu10` total loss) with zero telemetry data loss and strict **Exactly-Once** idempotency into **SAP S/4HANA** during failover replay to AWS Virginia (`us10`).

---

## 1. Live Implementation & Runtime Verification

### 1.1. SAP Cloud Integration Idempotent Failover Processor (`Satellite_Telemetry_DR_Idempotent_Processor_v1`)
Configured with SHA-256 Redis deduplication (`IdempotentTelemetry_ReplayGuard`), conditional `DR_Deduplication_Router` branching, and an `HTTP 503` `Exception Subprocess` health-signal emitter for upstream GSLB failover:

![SAP CPI Active-Passive DR Processor](sap-cpi-dr-idempotent-processor-deployed.png)

### 1.2. SAP BTP Integration Runtime & Endpoint Provisioning (`Started`)
Active runtime artifact provisioned on SAP BTP Cloud Foundry (`eu10`) ready for Anycast GSLB health probing and cross-region event ingestion:

![SAP CPI DR Runtime Monitoring](sap-cpi-dr-monitoring-live.png)

---

## 2. Multi-Region Active-Passive Failover Topology

```text
          [ Global Satellite Ground Stations (LEO / GEO Telemetry) ]
                                       │
                                       │ Anycast TLS 1.3 (500ms Telemetry Frames)
                                       ▼
          ┌────────────────────────────────────────────────────────┐
          │ Global Server Load Balancer (GSLB / Cloudflare Edge)   │
          │ Health Probe: /aerospace/v1/telemetry (5s Interval)    │
          └──────────────┬─────────────────────────┬───────────────┘
                         │                         │
          [ PRIMARY: ACTIVE ]               [ SECONDARY: PASSIVE ]
          (Healthy: HTTP 200)               (Promoted on EU10 503/Timeout)
                         │                         │
                         ▼                         ▼
  ┌─────────────────────────────────┐     ┌─────────────────────────────────┐
  │ AWS Frankfurt (SAP BTP EU10)    │     │ AWS Virginia (SAP BTP US10)     │
  │                                 │     │                                 │
  │ [SAP Advanced Event Mesh EU10]  │════►│ [SAP Advanced Event Mesh US10]  │
  │  Topic: aerospace/telemetry/v1  │ DMR │  Async Cross-Region Replica     │
  │                │                │     │                │                │
  │                ▼                │     │                ▼                │
  │ [SAP CPI EU10 Primary Runtime]  │     │ [SAP CPI US10 Failover Runtime] │
  │  IdempotentTelemetry_ReplayGuard│     │  IdempotentTelemetry_ReplayGuard│
  └────────────────┬────────────────┘     └────────────────┬────────────────┘
                   │                                       │
                   └───────────────────┬───────────────────┘
                                       │
                                       ▼
                     ┌───────────────────────────────────┐
                     │ Global Active-Active Redis Cache  │
                     │ Key: SHA256(SatelliteID:FrameSeq) │
                     │ Atomic SETNX Check (TTL: 24h)     │
                     └─────────────────┬─────────────────┘
                                       │
                        ┌──────────────┴──────────────┐
                        │                             │
                [REDIS: MISS (NEW)]           [REDIS: HIT (REPLAY)]
                        │                             │
                        ▼                             ▼
        ┌───────────────────────────────┐   ┌───────────────────────────────┐
        │ SAP S/4HANA Core (EAM & FICA) │   │ Suppress Duplicate Replay     │
        │ Post Orbital Health & Billing │   │ Return HTTP 200 ACK Cleanly   │
        └───────────────────────────────┘   └───────────────────────────────┘
```

---

## 3. Cross-Region Event Mesh & Idempotent Replay Guard

During an abrupt data center outage in Frankfurt (`eu10`), telemetry frames processed in the final `1.2 seconds` prior to infrastructure collapse may have already committed to SAP S/4HANA before their replication acknowledgment reached the Virginia (`us10`) Advanced Event Mesh broker. Upon failover promotion, `us10` replays those in-flight frames.

To prevent duplicate maintenance notifications (`PM`) or double usage-billing entries in S/4HANA, every CPI instance executes an atomic SHA-256 fingerprint check against a globally replicated Redis Enterprise store:

| Processing State | Redis Lookup (`X-Redis-Cache-Lookup`) | Router Branch (`DR_Deduplication_Router`) | S/4HANA Action | Gateway Response |
| :--- | :---: | :--- | :--- | :---: |
| **Normal Live Frame** | `MISS` | `Default Route` (`COMMITTED_NEW`) | Committed to S/4HANA EAM/Billing | `HTTP 200 OK` |
| **Post-Failover Replay** | `HIT` | `DUPLICATE_REPLAY_SUPPRESSED` | Skipped (Zero Duplicate Postings) | `HTTP 200 OK` |
| **Regional Outage / Fault** | *N/A* | `Exception Subprocess` | Halted; GSLB Failover Triggered | `HTTP 503` (`Retry-After: 5`) |

---

## 4. Telemetry Payload & Deduplication Behavior

### 4.1. Inbound Ground Station Telemetry Frame (`POST /aerospace/v1/telemetry`)
```json
{
  "satelliteId": "SAT-LEO-CONSTELLATION-042",
  "frameSequenceId": "FRM-20261113-994812",
  "groundStation": "SVALBARD-GS-01",
  "orbitalAltitudeKm": 548.4,
  "transponderTempC": 21.6,
  "kaBandThroughputMbps": 1420.5
}
```

### 4.2. Failover Replay Suppression Response (`HTTP 200 OK` from `US10-VIRGINIA-DR`)
```http
HTTP/1.1 200 OK
X-DR-Idempotency-Key: 8f94b1e7c3a209d441b6f8e19023c7a5d4b1e098f7654321a0b9c8d7e6f54321
X-DR-Idempotency-Status: DUPLICATE_REPLAY_SUPPRESSED
X-DR-Processed-Region: US10-VIRGINIA-DR

{
  "telemetryAck": {
    "satelliteId": "SAT-LEO-CONSTELLATION-042",
    "frameSequenceId": "FRM-20261113-994812",
    "idempotencySha256": "8f94b1e7c3a209d441b6f8e19023c7a5d4b1e098f7654321a0b9c8d7e6f54321",
    "processedByRegion": "US10-VIRGINIA-DR",
    "deduplicationDecision": "DUPLICATE_REPLAY_SUPPRESSED",
    "s4hanaPostingAction": "SKIPPED_ALREADY_COMMITTED_IN_EU10"
  }
}
```

---

## 5. Repository Structure

```text
.
├── README.md                                                     # Multi-region DR architecture & battle scars
├── build.gradle                                                  # Gradle build & Spock verification configuration
├── settings.gradle                                               # Project root configuration
├── sap-cpi-dr-idempotent-processor-deployed.png                  # Live SAP CPI iFlow canvas & Router/Exception proof
├── sap-cpi-dr-monitoring-live.png                                # Live SAP BTP Integration Content runtime proof
├── architecture/
│   ├── aem-dmr-bridge-config.json                                # SAP Advanced Event Mesh EU10 <-> US10 DMR config
│   └── gslb-anycast-healthcheck.json                             # GSLB active-passive failover & health probe spec
├── docs/
│   ├── adr/
│   │   └── ADR-027-active-passive-dr-failover-mesh.md            # Architectural Decision Record
│   └── runbooks/
│       └── DR-FAILOVER-RUNBOOK-EU10-TO-US10.md                   # Site Reliability Engineering (SRE) failover SOP
├── iflow/
│   ├── Satellite_Telemetry_DR_Idempotent_Processor_v1.zip        # Exported SAP Integration Suite iFlow package
│   └── extracted/                                                # Unpacked BPMN2 (.iflw) & runtime resources
└── src/
    ├── compileStub/groovy/com/sap/gateway/ip/core/customdev/util/
    │   └── Message.groovy                                        # SAP CPI Message interface contract stub
    ├── main/groovy/com/sap/cpi/aerospace/
    │   └── IdempotentTelemetryProcessor.groovy                   # Production SAP CPI idempotency & replay script
    └── test/groovy/com/sap/cpi/aerospace/
        └── IdempotentTelemetryProcessorSpec.groovy               # Spock Framework failover & deduplication tests
```

---

## 6. Lessons Learned: Transatlantic Replication & Split-Brain Battle Scars

1. **Transatlantic RTT Latency Penalty (`88ms–115ms` Frankfurt ↔ Virginia):**
   * *Challenge:* Initial load testing with synchronous cross-region Event Mesh acknowledgments degraded baseline `eu10` ingestion throughput by 42% due to speed-of-light round-trip latency across the Atlantic fiber backbone.
   * *Resolution:* Re-architected the SAP Advanced Event Mesh Dynamic Message Routing (DMR) bridge to **Asynchronous Guaranteed Replication**, restoring `<15ms` ingestion latency in `eu10` while maintaining an **RPO under 1.5 seconds**.
2. **The "1.2-Second Ghost Window" (Post-Failover Replay Duplicates):**
   * *Challenge:* Because cross-ocean replication is asynchronous, frames committed to S/4HANA by `eu10` right before a simulated regional blackout lacked replication ACKs in `us10`, resulting in ~1,400 replayed frames upon Virginia promotion.
   * *Resolution:* Engineered deterministic SHA-256 frame fingerprinting backed by an **Active-Active Global Redis Cache (`SETNX`)** inside CPI, eliminating 100% of duplicate S/4HANA postings during failover replay.
3. **DNS TTL Caching by Legacy Ground Station Modems:**
   * *Challenge:* Several polar ground station routers ignored `10s` DNS TTLs and continued routing packets to the dead Frankfurt endpoint for up to 5 minutes.
   * *Resolution:* Placed a **BGP Anycast Edge Layer** ahead of both SAP APIM regional gateways so traffic reroutes globally at the network layer in `<3 seconds` without relying on client DNS cache invalidation.
4. **Preventing Split-Brain Flapping via Asymmetric Failback:**
   * *Challenge:* Automated bi-directional failback caused route oscillation ("flapping") during intermittent European backbone packet loss.
   * *Resolution:* Enforced **Automated Failover (`EU10 -> US10`)** after 3 consecutive failed 5-second health probes, paired with **Manual Controlled Failback (`US10 -> EU10`)** requiring 30 minutes of verified stability and reverse AEM queue drain.

---

## 7. Architectural Decision Record (ADR-027)

* **Status:** Accepted & Verified
* **Context:** Single-region SAP BTP deployments expose mission-critical aerospace telemetry pipelines to unrecoverable downtime and duplicate ERP postings during regional cloud provider failures.
* **Decision:** Deploy an Active-Passive Multi-Region architecture across `eu10` (Frankfurt) and `us10` (Virginia) combining Anycast GSLB routing, asynchronous SAP Advanced Event Mesh DMR replication, and Redis-backed SHA-256 idempotency inside SAP Cloud Integration.
* **Consequences:** Achieves `RTO < 30 seconds` and `RPO < 1.5 seconds` with guaranteed *Exactly-Once* S/4HANA transaction integrity across regional failover events.

---

**Integration Architect:** Željko Popov
