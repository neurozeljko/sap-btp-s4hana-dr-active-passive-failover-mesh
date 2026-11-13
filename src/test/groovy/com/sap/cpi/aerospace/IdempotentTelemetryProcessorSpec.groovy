package com.sap.cpi.aerospace

import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Unroll

class IdempotentTelemetryProcessorSpec extends Specification {

    IdempotentTelemetryProcessor processor = new IdempotentTelemetryProcessor()
    Message message = Mock(Message)

    @Unroll
    def "Multi-Region DR Deduplication: #scenario (Region=#region, Redis=#redisState => #expectedDecision)"() {
        given: "An inbound satellite telemetry frame"
        def jsonPayload = """
        {
            "satelliteId": "SAT-LEO-042",
            "frameSequenceId": "FRM-994812",
            "orbitalAltitudeKm": 548.4
        }
        """
        message.getBody(String.class) >> jsonPayload
        message.getHeaders() >> [
            "X-Redis-Cache-Lookup": redisState,
            "X-Active-BTP-Region": region
        ]

        when: "The DR Idempotent Processor evaluates the frame"
        processor.processData(message)

        then: "Deterministic idempotency headers and S/4HANA routing flags are set"
        1 * message.setHeader("X-DR-Idempotency-Key", _ as String)
        1 * message.setHeader("X-DR-Idempotency-Status", expectedDecision)
        1 * message.setHeader("X-DR-Processed-Region", region)
        1 * message.setHeader("CamelHttpResponseCode", 200)
        1 * message.setBody(_ as String) >> { args ->
            def parsed = new JsonSlurper().parseText(args[0])
            assert parsed.telemetryAck.deduplicationDecision == expectedDecision
            assert parsed.telemetryAck.s4hanaPostingAction == expectedS4Action
        }

        where:
        scenario                          | region             | redisState | expectedDecision              | expectedS4Action
        "Normal EU10 ingestion"           | "EU10-FRANKFURT"   | "MISS"     | "COMMITTED_NEW"               | "POSTED_TO_S4HANA_EAM_BILLING"
        "US10 DR new frame after cutoff"  | "US10-VIRGINIA-DR" | "MISS"     | "COMMITTED_NEW"               | "POSTED_TO_S4HANA_EAM_BILLING"
        "US10 DR duplicate replay frame"  | "US10-VIRGINIA-DR" | "HIT"      | "DUPLICATE_REPLAY_SUPPRESSED" | "SKIPPED_ALREADY_COMMITTED_IN_EU10"
    }
}
