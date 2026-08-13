package com.houseofel.builder.toil;

import com.houseofel.builder.npc.Specialization;

import java.util.UUID;

/** What a pipeline stage needs to compute its multiplier for one ticket. */
public record TicketContext(UUID npcUuid, Specialization specialization, TicketKind kind,
                             int rawMinutes, String orderId) {
}
