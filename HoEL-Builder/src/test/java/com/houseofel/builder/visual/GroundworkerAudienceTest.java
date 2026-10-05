package com.houseofel.builder.visual;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class GroundworkerAudienceTest {
    private final UUID javaPlayer = UUID.randomUUID();
    private final UUID bedrockPlayer = UUID.randomUUID();

    @Test void raisedNameplateIsJavaOnlyIncludingPlayersIdentifiedAfterAttachment() {
        Set<UUID> floodgate = ConcurrentHashMap.newKeySet();
        var policy = new GroundworkerAudience<Object>(floodgate::contains);
        assertTrue(policy.showRaisedNameplate(javaPlayer));
        floodgate.add(bedrockPlayer);
        assertFalse(policy.showRaisedNameplate(bedrockPlayer));
        assertTrue(policy.showRaisedNameplate(javaPlayer));
        policy.clear(); // Recreated labels after reload/restart use the same live viewer policy.
        assertFalse(policy.showRaisedNameplate(bedrockPlayer));
    }

    @Test void simultaneousJavaAndBedrockAreIndependentAndUnrelatedModelsAreUntouched() {
        var policy = new GroundworkerAudience<Object>(bedrockPlayer::equals);
        var groundworker = new Object(); var unrelated = new Object();
        policy.attach(groundworker);
        IntStream.range(0, 1000).parallel().forEach(i -> {
            assertTrue(policy.cancelSpawn(groundworker, bedrockPlayer));
            assertFalse(policy.cancelSpawn(groundworker, javaPlayer));
            assertFalse(policy.cancelSpawn(unrelated, bedrockPlayer));
        });
    }

    @Test void lateJoinUsesCurrentFloodgateClassificationRatherThanAnAttachTimeSnapshot() {
        Set<UUID> floodgate = ConcurrentHashMap.newKeySet();
        var policy = new GroundworkerAudience<Object>(floodgate::contains);
        var tracker = new Object(); policy.attach(tracker);
        assertFalse(policy.cancelSpawn(tracker, bedrockPlayer));
        floodgate.add(bedrockPlayer); // Floodgate identifies a player joining after NPC attachment.
        assertTrue(policy.cancelSpawn(tracker, bedrockPlayer));
        assertFalse(policy.cancelSpawn(tracker, javaPlayer));
    }

    @Test void chunkReloadAndRestartUseNewTrackersAndDoNotRetainOldOwnership() {
        var policy = new GroundworkerAudience<Object>(bedrockPlayer::equals);
        var beforeUnload = new Object(); policy.attach(beforeUnload);
        assertTrue(policy.cancelSpawn(beforeUnload, bedrockPlayer));
        policy.detach(beforeUnload);
        assertFalse(policy.cancelSpawn(beforeUnload, bedrockPlayer));
        var afterReload = new Object(); policy.attach(afterReload);
        assertTrue(policy.cancelSpawn(afterReload, bedrockPlayer));
        assertFalse(policy.cancelSpawn(afterReload, javaPlayer));
        policy.clear();
        assertFalse(policy.cancelSpawn(afterReload, bedrockPlayer));
        var restarted = new GroundworkerAudience<Object>(bedrockPlayer::equals);
        var afterRestart = new Object(); restarted.attach(afterRestart);
        assertTrue(restarted.cancelSpawn(afterRestart, bedrockPlayer));
        assertFalse(restarted.cancelSpawn(afterRestart, javaPlayer));
    }
}
