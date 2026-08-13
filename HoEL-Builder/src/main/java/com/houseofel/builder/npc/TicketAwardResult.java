package com.houseofel.builder.npc;

import java.util.List;

/**
 * Outcome of one {@link HelperLevelService#awardTicket} call. {@code announcementLines}
 * is already-formatted in-character text ready to send to a player — empty unless this
 * ticket caused a level-up.
 */
public record TicketAwardResult(int finalToil, boolean leveledUp, int newLevel, List<String> announcementLines) {
}
