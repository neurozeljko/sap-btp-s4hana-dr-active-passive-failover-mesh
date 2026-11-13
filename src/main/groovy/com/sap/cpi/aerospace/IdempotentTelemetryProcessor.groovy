package com.sap.cpi.aerospace

import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.security.MessageDigest

def Message processData(Message message) {
    def body = message.getBody(String.class)
    if (!body || body.trim().isEmpty()) {
        throw new IllegalArgumentException("DR Critical Fault: Empty satellite telemetry frame.")
    }

    def headers = message.getHeaders() ?: [:]
    def slurper = new JsonSlurper()
    def payload = slurper.parseText(body)

    if (!payload.satelliteId || !payload.frameSequenceId || payload.orbitalAltitudeKm == null) {
        throw new IllegalArgumentException("Telemetry Schema Violation: Missing satelliteId, frameSequenceId, or orbitalAltitudeKm.")
    }

    String rawKey = "${payload.satelliteId}:${payload.frameSequenceId}"
    String idempotencyKey = MessageDigest.getInstance("SHA-256")
        .digest(rawKey.getBytes("UTF-8"))
        .encodeHex()
        .toString()

    String redisLookupState = (headers.get("X-Redis-Cache-Lookup") ?: "MISS").toString().trim().toUpperCase()
    String activeRegion = (headers.get("X-Active-BTP-Region") ?: "US10-VIRGINIA-DR").toString()

    boolean isDuplicateReplay = (redisLookupState == "HIT")
    String deduplicationStatus = isDuplicateReplay ? "DUPLICATE_REPLAY_SUPPRESSED" : "COMMITTED_NEW"

    message.setHeader("X-DR-Idempotency-Key", idempotencyKey)
    message.setHeader("X-DR-Idempotency-Status", deduplicationStatus)
    message.setHeader("X-DR-Processed-Region", activeRegion)
    message.setHeader("CamelHttpResponseCode", 200)

    def responsePayload = [
        telemetryAck: [
            satelliteId: payload.satelliteId,
            frameSequenceId: payload.frameSequenceId,
            idempotencySha256: idempotencyKey,
            processedByRegion: activeRegion,
            deduplicationDecision: deduplicationStatus,
            s4hanaPostingAction: isDuplicateReplay ? "SKIPPED_ALREADY_COMMITTED_IN_EU10" : "POSTED_TO_S4HANA_EAM_BILLING"
        ]
    ]

    message.setBody(JsonOutput.prettyPrint(JsonOutput.toJson(responsePayload)))
    return message
}
