package ca.musakaleem.remit.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Routing status change of one instruction, published by routing-service to {@code
 * payments.status-events}.
 *
 * @param idempotencyKey instruction key
 * @param batchId batch the instruction arrived in
 * @param status new status
 * @param channel channel the instruction was routed to
 * @param detail optional human readable detail
 * @param occurredAt when the status changed (UTC)
 */
public record InstructionStatusEvent(
    String idempotencyKey,
    UUID batchId,
    InstructionStatus status,
    Channel channel,
    String detail,
    Instant occurredAt) {}
