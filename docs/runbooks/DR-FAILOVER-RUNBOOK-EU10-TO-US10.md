# SRE Runbook: Regional Failover & Controlled Failback (EU10 Frankfurt ↔ US10 Virginia)

## Phase 1: Automated Failover Detection (T+0s to T+30s)
1. Primary `eu10` CPI emits `HTTP 503` (`Retry-After: 5`) or times out across 3 consecutive 5-second health probes.
2. Anycast GSLB shifts 100% of ground station ingress traffic to `us10` Virginia APIM/CPI endpoints.
3. `us10` AEM queue consumers unpause and drain replicated in-flight telemetry frames through `IdempotentTelemetry_ReplayGuard`.
4. Global Redis cache suppresses any duplicate frames already committed by `eu10` prior to outage.

## Phase 2: Controlled Manual Failback (Post-Incident Recovery)
1. Confirm `eu10` BTP subaccount health and S/4HANA core connectivity for a minimum 30-minute stabilization window.
2. Enable reverse DMR replication (`US10 -> EU10`) to synchronize backlog state.
3. Shift GSLB primary weight back to `eu10` in 10% -> 50% -> 100% canary increments.
