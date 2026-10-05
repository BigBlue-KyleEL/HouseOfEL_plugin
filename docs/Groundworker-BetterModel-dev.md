# Groundworker BetterModel dev integration â€” 2026-10-04

> **Current accepted state:** see [the consolidated Claude Code handoff](Groundworker-CLAUDE-CODE-HANDOFF.md). It supersedes historical hashes/timings and pending Java feedback below. Kyle accepted the final working speed, original level-up pacing and accessory fix.

**Current official asset:** the accessory rest-key update is promoted, SHA-256 `da84343c3abbf8ea40b48187f04b99f32d8d741a7cdf0fd3aedadea6a0ef268b`. Dev uses a byte-identical copy of Final V1. Shipped animation-speed default is 2.0, feature enabled remains false; levelup/recovery remain at 1.0x. The consolidated handoff records current deployment verification; older sections below describe historical builds.

Implemented on `dev` in Plugin-Source; deployed only to `D:\Projects\House of EL\Local Dev Server 26.1.2`. No merge, live-server edit, or resource-pack release was performed.

## Asset and plugin contract

BetterModel **3.0.2**, official Paper jar: https://github.com/toxicity188/BetterModel/releases/tag/3.0.2 . Runtime logs confirm Minecraft **26.1.2**, NMS **V26_R1**, Paper, Java 25. API dependency is `compileOnly("io.github.toxicity188:bettermodel-bukkit-api:3.0.2")`, with BetterModel in `softdepend`. The feature flag defaults to **false** in the shipped Builder configuration, and is **true only in dev**.

The deployed `plugins/BetterModel/models/groundworker.bbmodel` is byte-identical to the approved `Final V1/groundworker_v1.bbmodel`: SHA-256 `da84343c3abbf8ea40b48187f04b99f32d8d741a7cdf0fd3aedadea6a0ef268b`. **Head-follow is intentionally off (Kyle's Java-test decision, 4 October 2026).** The `head` bone retains its original name and has no tag; head motion comes only from the authored animations. UUIDs and all ten animations remain unchanged, including the intentional recovery step. Deployment uses a direct byte copy, with no model-editing/tagging step. Animation numeric strings and JSON number tokens contain no exponent notation.

The installed BetterModel API defaults to `ModelRotator.YAW`, whose `ModelRotation.yaw()` returns `(0, yaw)`. Its body rotator supplies entity pitch internally, but this strategy strips that pitch before the whole-model transform. Head look modifiers target tagged bones only; the approved asset has no such tags. HoEL's visual service supplies no pitch or head look modifier. Body yaw tracking remains enabled, and saved Citizens head pitch is not changed.

Both independent roots, `groundworker` and `shovel_motion`, are retained (17 bones). The source Citizens entity remains a VILLAGER with its existing hitbox and navigation. No hitbox or mount tags are introduced. `held_item` is unused. Stone and every other Groundworker job use `dig`.

## Runtime behavior

A per-NPC tracker attaches on spawn, chunk load, and restart. A one-second scan covers specialization assignment after spawn and recovers from model reloads. Despawn/removal/plugin shutdown closes the tracker and removes the raised TextDisplay nameplate; the original Citizens nameplate setting is restored. The Java-only raised plate is 2.6 blocks above the Citizens location, configurable without moving the entity.

The controller uses actual entity movement for `walk`; stationary baseline is `idle`, or `rusted_idle` while Rusted. Working means an existing, unpaused job. Session start runs `shovel_draw` once (70 ticks), then moving/work loops; session end runs `shovel_stow` once (70 ticks). Pause/resume closes/opens a work session. `place` events follow actual block placement hooks; events coalesce to at most one pending clip, dropping new events while one is queued or playing, without slowing jobs (24 base ticks). Right-click queues one 42-tick `greet` per interaction. Level increases trigger `levelup` (90 ticks) â†’ `levelup_recover` (110 ticks) â†’ stationary baseline for the intentional snap. An interrupted ongoing work session resumes without another draw.

BetterModel's built-in idle/walk clips are removed when the viewer scheduler first starts. Draw uses `HOLD_ON_LAST`; every transition explicitly stops the previous named clip, releasing the hold. Entry and exit interpolation are **zero ticks**, so cross-clip Euler blending cannot invent a 360Â° turn. This leaves approved within-clip movement untouched, including recovery's equivalent-angle step. The installed API preserves original key times when inserting lerp frames and explicitly treats `step` as discontinuous. The approved shovel keys have `step` at 0.00 s (`-702.2467`) followed by the equivalent `-342.2467` at 0.05 s. Actual client rendering of that step still needs the Java checklist below.

**Bedrock correction (5 October):** the earlier `viewFilter` did not prevent model spawn registration, so BetterModel still hid the villager through per-viewer metadata. Kyle confirmed the failure. The fix cancels BetterModel's `ModelSpawnAtPlayerEvent` for Floodgate viewers of our Groundworker trackers, preventing the model spawn and source hide together. Java still sees the model with the villager hidden. See [the visibility fix report](Groundworker-Bedrock-visibility-fix.md); Kyle confirmed the villager is visible. The duplicate-name follow-up makes the raised label Java-only, leaving Bedrock its native villager name; Kyle confirmed that single-name retest passed with Java and Bedrock together. GeyserModelEngine remains out of scope.

## Resource pack and dev startup

The dev server's existing released GUI pack v0.4.0 was downloaded as an input; it was not republished. BetterModel's `plugins/BetterModel/build.zip` is merged into it locally. The merged pack contains **62 files**, preserves the GUI assets and BetterModel's modern overlay, and is served at:

`http://192.168.1.52:8765/HoEL-Dev-Groundworker.zip`

Dev `server.properties` points there with SHA-1 `bd6c1abc78da84691505c7c5dc1ed5992ab81a5c`. HTTP GET returned 200 and the downloaded hash was checked. The Python server serves only `dev-resource-pack`, not the server directory. `start-headless.ps1` now starts that local host if port 8765 has no listener. It binds locally/LAN; no release or remote hosting was created. If the PC's LAN IP changes, edit the URL in `integration-tools/groundworker/prepare_pack.py`, rerun it, and restart dev. Remote/WAN Voxyris-Dev clients would need a reachable dev-only URL; Windows firewall access from a second machine is untested.

After a future BetterModel reload or model change, run `integration-tools/groundworker/prepare_pack.py` with Python and restart dev to refresh the pack SHA-1. This script rejects conflicting files rather than overwriting them. Keep `pack.mcmeta`'s BetterModel overlay metadata. A backup of the original dev server.properties, Builder jar, and startup script is under `integration-tools/groundworker`.

Later main-pack integration: use the current main HoEL GUI pack as the base, copy generated BetterModel assets plus `bettermodel_modern/`, and merge `pack.mcmeta` overlay entries. Review any colliding paths; don't simply overwrite the main pack's metadata or model definitions. Preserve BetterModel item/model mappings and its configured namespace. Test the merged Java pack and calculate its SHA-1 before an explicitly authorized main-pack release. Bedrock conversion is a separate future step.

## Files changed

Repository, all under `HoEL-Builder`:
- `build.gradle.kts`: compile-only BetterModel API.
- `src/main/resources/plugin.yml`: optional dependency; diagnostic command and explicit permission (default false).
- `src/main/resources/config.yml`: disabled-by-default feature and visual settings.
- `src/main/java/com/houseofel/builder/visual/{HelperVisuals,GroundworkerAnimation,GroundworkerModelService}.java`: optional visual interface, controller, Citizens lifecycle and Java-only model tracking.
- `src/main/java/com/houseofel/builder/HoELBuilder.java`: flag/bootstrap/shutdown wiring.
- `src/main/java/com/houseofel/builder/npc/HelperLevelService.java`: visual level-up notification, with XP rules unchanged.
- `src/main/java/com/houseofel/builder/job/{JobManager,ClearJobTask,QuarrymanJobTask,LandscaperJobTask,CofferdamJobTask,ShaftMinerJobTask}.java`: placement notifications, with job timing/pathing unchanged.
- `src/test/java/com/houseofel/builder/visual/GroundworkerAnimationTest.java`: six controller tests.
- `docs/Groundworker-BetterModel-dev.md`: this report.

Dev-only: `plugins/bettermodel-3.0.2-paper.jar`, `plugins/BetterModel/config.yml`, approved model byte copy and generated pack, deployed Builder jar, `plugins/HoEL-Builder/config.yml`, `server.properties`, `dev-resource-pack/HoEL-Dev-Groundworker.zip`, `start-headless.ps1`, and supporting scripts/backups/logs under `integration-tools/groundworker`. No source model edit.

## Validation

`gradlew.bat build --console=plain` passes across all modules, including **74 Builder tests** (six new controller tests). Controller tests cover once-per-session draw/stow, ending during draw, Rusted/movement baseline, queued actions, level-up/recovery timing and intentional snap, and returning to an existing session without another draw.

Builder deployment SHA-256: **40404793a0556c14b5c383e6590d45351980f4afe4348dbf5ec89d86b1994542**.

The head-rotation removal build reran every Gradle task and all 74 Builder tests successfully. The final dev restart enabled Builder at host log time 16:54:52 and reached Done at 16:54:58; the deployed jar matches the tested build. The model’s approved SHA-256 was confirmed again after restart. The dev startup enabled BetterModel and the updated Builder with no ERROR/Exception entries. All six existing Groundworkers (#7â€“#12) attached after Citizens loaded. A temporary force-load of chunk [4,-5] reattached NPC #8 after chunk unload, selecting `rusted_idle` from its actual Rusted record. Headless visual previews accepted `levelup`, `shovel_draw`, `place`, `greet`, and `dig`; logs show the complete levelup â†’ levelup_recover â†’ rusted_idle sequence and held draw â†’ stow â†’ rusted_idle release. The temporary force-load was removed afterward. These are server/controller checks, not a claim that a client rendered the poses.

Existing unrelated warnings remain: unresolved example MythicMobs dialog skills, ViaVersion update notice, and Java Unsafe deprecation. Paper's notice about 26.2 does not change the agreed 26.1.2 target.

## Test in Voxyris-Dev (Java)

1. Join Voxyris-Dev and accept the mandatory merged pack. Confirm GUI textures still work. Stand near a Groundworker and run `/helpermodel status`. If none are attached while standing near one, check logs; no viewers/unloaded NPC chunks can legitimately produce attached=0.
2. Grant the existing test permission from console/LuckPerms: `lp user <your-name> permission set houseofel.builder.modeltest true`. OP alone is not the permission grant. `/helpermodel play <npcId> <clip>` changes visuals only, not jobs, levels, or Rusted state. Run one preview at a time and let it finish; loop previews last five seconds.
3. Inspect the table below, using your current Groundworker ID from status. Existing #8 Montgomery is at approximately world (74,45,-75) and is Rusted; other existing helpers are also available without creating test NPCs.

| Clip / flow | What to look for |
|---|---|
| `idle` | Authored breathing/head motion only; no extra tilt from saved NPC pitch. |
| `walk` | Approved forward gait. Then have the helper follow/move through the existing job UI and verify real movement selects walk and stopping returns to the appropriate baseline. |
| `rusted_idle` | Approved Rusted idle; actual Rusted helper chooses it while stationary. Preview does not rust/restore a helper. |
| `shovel_draw` | Approved reach/draw; no extra turn or sink; held end releases into stow when no work session is active. |
| `dig` | Approved two-handed digging, including a real stone Quarryman job. |
| Real work session | One draw at start, dig while working, walk while traveling, one stow at completion/pause/cancel. Multiple blocks must not repeat draw/stow. Use an ordinary disposable dev job through the existing UI; gameplay is unchanged. |
| `shovel_stow` | Approved reversed draw, once; clean return to baseline. |
| `place` | One approved placement clip; a real fill/repair job produces placement notifications. Bursts coalesce to at most one pending visual clip; events while queued/playing are dropped without delaying block placement. |
| `greet` | One greeting. Also right-click the NPC and confirm the existing menu still opens. |
| `levelup` | Automatically plays all 4.5s of levelup, then all 5.5s of recovery, then the intentional snap to baseline. Shovel stays where the approved clip says; inspect recovery's first 0.05s for an unwanted spin. No level change is needed to preview. |
| `levelup_recover` | Standalone recovery preview is available; the complete `levelup` preview is the meaningful handoff check. |
| Lifecycle | Leave/re-enter the NPC's chunk and reconnect; only one model/nameplate should appear. Despawn/removal should remove both. Do lifecycle operations only on a disposable dev NPC if changing its spawn/removal state. |
| Nameplate and pathing | Plate clears the head; clicks and Citizens navigation use the unchanged villager entity/hitbox. |

## Remaining checks and open questions

No build/install/deploy blocker remains. Visual acceptance needs a Java client: authored head motion without NPC pitch, pack acceptance, grip/transition rendering, no added turn, intentional recovery snap, and unchanged GUI/pathing. A Bedrock client should confirm the original villager fallback. LAN pack reachability from other machines has not been tested. No gameplay or approved-animation decision was made; any subsequent change to those needs Kyle's approval. GeyserModelEngine integration still waits on the agreed target-version support.
