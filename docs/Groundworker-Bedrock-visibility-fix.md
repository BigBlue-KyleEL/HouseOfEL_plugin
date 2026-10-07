# Groundworker Bedrock base-villager visibility fix — 5 October 2026

> **Note:** "HoEL" was renamed to "HEL" on 2026-10-07. Paths and names below reflect the pre-rename state.

Kyle's Floodgate test (`.KyleEL9084`) disproved the earlier fallback claim: Montgomery had a nameplate and faint invisible-entity silhouette, but no visible villager. The previous report inferred visible fallback from an API viewer filter without a Bedrock client test. That inference was wrong.

## Exact cause in BetterModel 3.0.2

This is **per-viewer packet metadata**, not global Bukkit/Citizens invisibility. HoEL changes Citizens nameplate visibility only, and uses a separate raised TextDisplay. It does not call `setInvisible` on the NPC. BetterModel's source entity flags remain the underlying source data.

Verified against the downloaded source of the installed 3.0.2 release:

- `api/.../data/renderer/RenderPipeline.java`: `viewFilter` is used by `viewedPlayer()` for subsequent model updates. `spawn()` does not test it; it puts the viewer in `playerMap` and adds spawn packets. Thus our old filter did not prevent Floodgate players becoming spawned-model viewers.
- `api/.../tracker/EntityTrackerRegistry.java`: `PlayerChannelCache.reapplyHideOption()` combines hide options of trackers whose `isSpawned(viewerUuid)` is true. The tracker default hides the source body/equipment/fire/glowing.
- `nms/v26_R1/.../NMSImpl.kt`: the injected outgoing channel handler rewrites `ClientboundSetEntityDataPacket` only for a registry spawned for that player. Registry spawn also explicitly sends the hidden metadata through `NMS.hide()` after the model spawn bundle.
- `nms/v26_R1/.../Functions.kt`: `entityFlag()` sets bit 5 (`0x20`, invisible) in shared entity flags when that viewer's hide option requests visibility hiding. The packet is rebuilt; the underlying entity metadata is not globally toggled.

The Bedrock client cannot display this Java-only model, but still received invisible-villager metadata because the model was considered spawned for that viewer. Filtering display updates was too late.

## Fix

HoEL now subscribes to BetterModel's supported **`ModelSpawnAtPlayerEvent`** and cancels it for Floodgate UUIDs **only for trackers owned by this Groundworker visual service**. The former render `viewFilter` is removed.

`Tracker.spawn()` calls this cancellable event before `RenderPipeline.spawn()` and returns immediately on cancellation. The denied viewer never enters the model's spawned-player map, receives no model spawn bundle, and does not satisfy the NMS `isSpawned(playerUuid)` condition for metadata rewriting. When all model spawns are denied, the registry bundle stays empty, so the explicit post-spawn hide send is skipped too. The registry can have an initial cached default hide option for a registered player; that alone does not trigger interception because the per-tracker spawned check is false.

Java events remain uncancelled: Java continues to receive the model and BetterModel's source-hiding packets. A Java viewer's decision cannot change a Bedrock viewer's decision. No global invisibility change, PacketEvents dependency, extra packet rewriting, entity replacement or hitbox/navigation change is needed.

Ownership is registered in the renderer's pre-update callback, before registry `refreshSpawn()` can spawn for already-nearby viewers. It is removed through the tracker's close callback; the listener is unregistered and ownership cleared at service shutdown. The concurrent ownership set supports event calls from different viewer contexts. Floodgate identity is queried at each spawn rather than taking a snapshot of players when the NPC attaches.

## Lifecycle coverage and checks

- Late Bedrock join: the normal entity-add/tracking path calls registry spawn, which invokes the gate with the joining player's current Floodgate identity.
- Chunk unload/reload: the old tracker closes and loses ownership; the replacement registers ownership before its first spawn.
- Restart: the service installs its listener before scanning/creating its owned trackers. Attachment replaces prior model trackers through the normal lifecycle; source state is not saved as invisible by this fix.
- Concurrent Java + Bedrock: each spawn event carries its own player UUID; only Bedrock events are cancelled.
- Detach/removal: BetterModel's existing close path restores source metadata, while HoEL removes its nameplate and ownership.

`GroundworkerAudienceTest` covers concurrent mixed viewers plus unrelated trackers, late Floodgate classification after attachment, and ownership cleanup/replacement after chunk reload/restart. These are policy/lifecycle tests, not a Bedrock client emulator. Full Gradle build and all **85 tests** passed, zero failures/errors/skips, including the official-model animation probe. The actual client matrix below remains a separate acceptance check; do not infer it from unit tests.

## Deployment and testing

Changed source:

- `D:/Projects/House of EL/Plugin-Source/HoEL-Builder/src/main/java/com/houseofel/builder/visual/GroundworkerAudience.java`
- `D:/Projects/House of EL/Plugin-Source/HoEL-Builder/src/main/java/com/houseofel/builder/visual/GroundworkerModelService.java`
- `D:/Projects/House of EL/Plugin-Source/HoEL-Builder/src/test/java/com/houseofel/builder/visual/GroundworkerAudienceTest.java`

The consolidated and original integration reports are corrected to describe the failed filter and the new spawn gate. No model, animation, speed, resource pack, gameplay, or dependency change was made.

Built/deployed Builder SHA-256: `e09c470cccc698b6a882249211f3faad5e6f9cec2d16087101aa3225eb6c4ef3`. Backed up the previous jar as `integration-tools/groundworker/HoEL-Builder-before-bedrock-visibility-20261005.jar`. Confirmed zero players, stopped dev gracefully over RCON, copied the tested jar and restarted Java 25. Only Local Dev Server 26.1.2 was deployed.

For a client retest, join as `.KyleEL9084` near Montgomery, then join with Java at the same time. Bedrock should see a normal villager and its native nameplate; Java should see only the custom model and plate. Repeat Bedrock reconnect after the NPC is already spawned, leave/re-enter its tracking/chunk range, and repeat after a dev restart.

`/helpermodel status` now reports the actual source `invisible` state and each online same-world viewer's `bedrock` classification and `model spawned` state. Near Montgomery, expect source invisible=false; `.KyleEL9084`: bedrock=true, model spawned=false; nearby Java: bedrock=false, model spawned=true. A distant/untracked Java viewer can correctly report false. Dev debug logs include `Bedrock spawn excluded: <UUID>` when the real spawn event is cancelled. Console/RCON can inspect without changing gameplay; the existing player test permission remains required.


Post-deploy verification: Paper reached Done at 16:48:45 on 5 October (Asia/Manila); all six Groundworkers attached and startup explicitly logged the Java-only spawn gate. No ERROR/Exception entries. A temporary force-load of Montgomery's previously unloaded chunk [4,-5] reattached NPC #8 and `/helpermodel status` reported `source invisible=false`, confirming the base villager is not globally invisible. The temporary force-load ticket was removed and its absence verified. The official model SHA-256 stays `da84343c3abbf8ea40b48187f04b99f32d8d741a7cdf0fd3aedadea6a0ef268b`; dev pack SHA-1 stays `767ddcab6af637f1c0f1c0afd2181dea8f74bef1`. At this earlier checkpoint Kyle chose to test later; server lifecycle and audience-policy checks passed. The final client acceptance below supersedes that pending status.


## Duplicate nameplate follow-up — 5 October 2026

Kyle's subsequent Bedrock screenshot confirms the base villager is now visible, but showed its native name alongside HoEL's raised TextDisplay. The raised label is now Java-only: it starts with visible-by-default disabled before spawn, and Java players are explicitly shown it. Floodgate viewers keep the native villager name. Existing viewers are handled on NPC attachment; joining players and world changes refresh the same policy next tick. Recreated labels after chunk reload/restart start hidden again. Citizens nameplate suppression for Java and BetterModel's viewer gate remain unchanged.

The status command also reports `raised nameplate` per viewer (expected false for Bedrock, true for Java; this is eligibility, not proof the entity is in tracking range). Added a nameplate audience regression test. Full rebuild: 86 tests passed, zero failures/errors/skips. Deployed Builder SHA-256: `436939470223629b6468aa6d42654a760dde638b23dae1e54855f95f6c5e532a`. Dev was empty and stopped gracefully before deployment/restart. No model, pack, pacing, or gameplay edits. Kyle confirmed that Java is unchanged and Bedrock now has a single nameplate, with both clients viewing Montgomery together. All requested client tests have passed.
