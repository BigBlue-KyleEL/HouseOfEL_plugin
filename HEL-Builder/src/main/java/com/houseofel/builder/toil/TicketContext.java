package com.houseofel.builder.toil;

import com.houseofel.builder.npc.Specialization;

import java.util.UUID;

/**
 * What a pipeline stage needs to compute its multiplier for one ticket.
 *
 * <p>{@code dailyCumulativeBefore} is read once by {@code HelperLevelService.awardTicket}
 * before the pipeline runs, so both the daily-taper stage's check AND the later write to
 * {@code DailyTaperStore} reuse the SAME read rather than each querying SQLite separately
 * for what's guaranteed to be the identical row.
 */
public record TicketContext(UUID npcUuid, Specialization specialization, TicketKind kind,
                             int rawMinutes, String orderId, long timestamp, int dailyCumulativeBefore) {
}
