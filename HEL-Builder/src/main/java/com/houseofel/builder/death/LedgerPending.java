package com.houseofel.builder.death;

import java.util.UUID;

/** One Helper's currently dropped-but-uncollected Work Ledger book — internal to {@code death}. */
record LedgerPending(UUID npcUuid, long droppedAt) {
}
