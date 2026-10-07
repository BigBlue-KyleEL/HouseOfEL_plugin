# Groundworker — Claude Code handoff

## Pre-release update — 6 October 2026

The current dev changes supersede the former 2x greeting policy: `greet`, `levelup` and `levelup_recover` use 1.0x in runtime sampling, controller deadlines and previews; other clips retain configured speed 2.0. The model and pack are unchanged. Job diagnostics now use `helpers.diagnostics.enabled: false` (graceful restart required). `/builder setlevel` and `/builder testpanel` have separate default-false permissions. See [the pre-release implementation report](Pre-release-2026-10-06.md) for configuration, command gates, audit, build and local deployment evidence. These changes remain uncommitted pending Kyle’s in-game check; the 5 October acceptance below covers the previous build.

## Final client acceptance — 5 October 2026

Kyle confirmed all client tests passed: Java visuals, levelup/recovery, the floating-accessory fix, 2x working pacing, and Bedrock visibility with both clients together. Java retains the custom model and raised label; Bedrock has a visible native villager and a single native nameplate. This supersedes pending-client-test notes in the historical sections and reports. The latest full build passed 86 tests with zero failures, errors, or skips, including the official-model probe.

Kyle authorized committing only this integration's code, tests, config, and docs on `dev`. No build outputs, server files, model binaries, release, push, merge to main, or live-server changes are included.

Updated 5 October 2026. This report describes the **current client-accepted dev integration**, including the subsequent fixes. Use it in preference to earlier deployment hashes, speed tables, and pending-test statements in the historical reports. Kyle's latest client feedback after the level-up pacing exemption was: **“Yeyyy noice it's perfecy.”** The 6 October update above supersedes this historical acceptance for the newly changed greeting and diagnostics/permissions.

## Accepted result and scope

The Groundworker renders through BetterModel 3.0.2 on Paper 26.1.2. Kyle confirmed that the temporary accessory-rest-key model stops the floating headband/apron/sash, the accelerated working animation now looks right, and the original-paced level-up/recovery is accepted. Preserve this behavior.

- Repository: `D:\Projects\House of EL\Plugin-Source`, branch **dev**. The accepted integration was subsequently committed through `18e88af`. The 6 October pre-release changes remain **uncommitted**; inspect `git status` before staging. No main merge, push, live deployment or pack release is authorized in this session.
- Only deployment target: `D:\Projects\House of EL\Local Dev Server 26.1.2` / Voxyris-Dev. The older `Local Dev Server` folder is a different, superseded target.
- Minecraft/Paper **26.1.2**, dev server jar `paper-26.1.2-74.jar`, NMS `V26_R1`, **Java 25**. Do not upgrade the target as part of this work.
- BetterModel **3.0.2**, official Paper release: https://github.com/toxicity188/BetterModel/releases/tag/3.0.2 . `compileOnly("io.github.toxicity188:bettermodel-bukkit-api:3.0.2")`; `softdepend: BetterModel` in Builder's plugin descriptor.
- Citizens entity type, hitbox, navigation, jobs, XP and block-work timing remain unchanged. The custom model is a visual layer. Quarryman pathing was tuned against the existing entity hitbox.
- Bedrock model conversion / GeyserModelEngine remains out of scope pending target-version support. Any future gameplay or approved-animation decision requires Kyle's approval.

Read the repository conventions at `D:\Projects\House of EL\Plugin-Source\CLAUDE.md`. The design record is `D:\Projects\House of EL\Obsidian\Archives\House of EL - Archives\Plugin\Helper NPCs\Helper NPC Visual Design.md`.

## Asset contract — official rest-key update complete

The authoritative model is `D:\Projects\House of EL\MC 3D Assets\Helper NPC Designs\Groundworker\Final V1\groundworker_v1.bbmodel`.

SHA-256: `da84343c3abbf8ea40b48187f04b99f32d8d741a7cdf0fd3aedadea6a0ef268b`

The official candidate `groundworker_v1_restkeys.bbmodel` matched its expected SHA-256, passed semantic comparison, and was renamed to the authoritative filename on 5 October 2026. Exactly 120 linear constant rest keys were added: position/rotation `(0,0,0)` at the start/end of all ten clips on `headband`, `apron`, and `shovel_sash`. All other JSON data matched the prior approved snapshot. Compared with the successful experiment (`030c1b7891dc030662da53ef47cd61e7bc627dfe870ee6ac197b762e1e073ad7`), only new keyframe UUIDs and list ordering differ; motion is semantically identical. Kyle client-confirmed this floating-accessory fix.

The previous official model is preserved in Final V1 as `groundworker_v1_before_restkeys_20261005_140248.bbmodel`, SHA-256 `3292f15168838738c2590f9f155c1104f69024e369c354879eb4134fe9c7fea5`. Its handoff was backed up as `CLAUDE_CODE_HANDOFF_before_restkeys_20261005_140248.md` before updating `CLAUDE_CODE_HANDOFF.md`.

Dev model destination: `D:\Projects\House of EL\Local Dev Server 26.1.2\plugins\BetterModel\models\groundworker.bbmodel`. The deployed model is byte-identical to the new Final V1 (verified after startup). The old experimental file is historical. See the final deployment verification below.

The rig remains Generic/free format 5.0, 42 cubes, 17 bones, ten clips, articulated knees/elbows, embedded 512×512 texture and 128×128 UV space. It descends from Sol articulated v3 and Kyle's authored poses. The final digging clip is `dig`; old `dig_shovel`/`shovel_dig` names are absent. The separate asset handoff in Final V1 retains the detailed rig/animation contract, including the recovery clip and independent shovel root.

### Preserve these authored decisions

- No head-follow. `head` retains its original untagged name and moves only from authored animation. The earlier head-follow experiment copied saved Citizens pitch (Montgomery 60° up, others roughly 35° down), so Kyle explicitly removed it. No tagging step remains. Do not edit NPC pitch to work around it.
- BetterModel's default `ModelRotator.YAW` strips entity pitch from whole-model orientation; body yaw tracking remains wanted. HoEL supplies no extra head-look or pitch modifier.
- Two independent roots: `groundworker` and `shovel_motion`. `shovel` stays under `shovel_motion`, never under `body` or `groundworker`. Root/body walking must not drag the landed shovel.
- `held_item` remains unused. No server-side tool attachment. **Every Groundworker job, stone included, uses `dig`.**
- In `levelup`, the shovel releases around 1.4s, lands at 2s and retains its authored local position/rotation after landing. Preserve Kyle's throw, approach and final pose.
- `levelup_recover` starts from the level-up end pose, picks up/stows the shovel and walks home. Its last orientation then snaps to idle **intentionally**. Do not smooth that snap or add a corrective turn.
- Recovery's equivalent-angle step at 0.05s must not be interpolated into a full turn. Authored X values near −702.2467° and −342.2467° represent the same orientation. Preserve the step.
- Future animation number strings must be ordinary decimals, never exponent notation.

## Visual service and animation state machine

`GroundworkerModelService` attaches an `EntityTracker` to Groundworker Helpers on NPC spawn, chunk load and restart; a one-second scan covers late specialization assignment and tracker recovery. Despawn/removal/shutdown closes the tracker and removes its text plate. It does not replace/mount/scale/navigate the Citizens entity. Both independent roots are checked before accepting the model.

A Java-only raised TextDisplay plate follows the entity at configurable **2.6 blocks** and clears the model. Original Citizens plate visibility is stored, suppressed while attached and restored on detach.

Animation behavior:

| Trigger | Result |
|---|---|
| Stationary default | `idle` |
| Entity actually moving | `walk` |
| Stationary Rusted helper | `rusted_idle` |
| Start an existing unpaused work session | `shovel_draw` once, then work/movement loops |
| Working stationary | `dig` loop |
| Work session ends / pauses | `shovel_stow` once, then baseline |
| Block placement | `place` once, with coalescing below |
| Player right-click | `greet` once; existing interaction/menu still runs |
| Level increases | `levelup` → `levelup_recover` → intentional baseline snap |

Draw/stow are per session, never per block. Returning to an existing session after level-up does not trigger a second draw. Existing job processing never waits for the visual animation.

BetterModel's automatic idle/walk states are stopped when its viewer scheduler first starts. HoEL explicitly stops old named clips on transitions. Entry/exit blending is zero, avoiding an added cross-clip Euler turn. Timed clips play their motion once and hold the final pose until HoEL releases them; loops repeat normally. Every keyed bone must apply its final pose before the controller permits a timed transition, followed by one server tick for final display interpolation. This accommodates the independent renderer/server clocks and prevents premature cutoffs/reset gaps. Recovery still transitions straight to baseline without an added smoothing clip.

### Placement-burst fix

The former integer `placements` backlog was replaced by a boolean pending event. **At most one pending place clip** exists. Drop new placement events while a clip is already queued or playing. A burst of 100 placements, including another burst while playing, cannot leave minutes of `place` clips after a job ends. Regression tests cover this and acceptance of a fresh event after completion. Gameplay placements are never delayed.

## Accepted speed policy

Shipped config defaults to feature **disabled** and speed **2.0** (Kyle’s 5 October decision). The service also defaults a missing speed setting to 2.0. Invalid values retain the existing warning/fallback to 1.0. Dev alone enables the feature:

```yaml
helpers:
  custom-model:
    enabled: true
    model: groundworker
    animation-speed: 2.0
    nameplate-height: 2.6
    debug: true
```

**`greet`, `levelup` and `levelup_recover` always use 1.0x**, regardless of the general speed setting. Kyle explicitly rejected faster celebration pacing and accepted the restored pacing; the 6 October deploy decision also restores the authored greeting pace. Sampling, controller deadlines and standalone previews share this exception.

| Clip | Authored motion | Accepted dev motion | Timed controller deadline |
|---|---:|---:|---:|
| `idle` | 4s | 2s | loop |
| `walk` | 1.2s | 0.6s | loop |
| `dig` | 1.8s | 0.9s | loop |
| `rusted_idle` | 4.5s | 2.25s | loop |
| `shovel_draw` | 3.5s | 1.75s | 35 ticks |
| `shovel_stow` | 3.5s | 1.75s | 35 ticks |
| `place` | 1.2s | 0.6s | 12 ticks |
| `greet` | 2.1s | **2.1s** | **42 ticks** |
| `levelup` | 4.5s | **4.5s** | **90 ticks** |
| `levelup_recover` | 5.5s | **5.5s** | **110 ticks** |

These are nominal motion durations; engine startup, loop boundaries and final display/server-tick synchronization add small cadence overhead. Timed transitions require both the deadline and the final-pose completion guard. Loop preview window scales from five seconds to 2.5 seconds at dev speed. Maintenance scans and Rusted polling do not accelerate. Invalid nonpositive/nonfinite speed falls back to 1.0 with a warning.

### Why native BetterModel speed was insufficient

BetterModel 3.0.2 renders on a **25ms / 40Hz** scheduler and consumes at most one keyframe per renderer tick. Its importer rounds 12.5ms keyframe deltas to zero. Most authored clips have dense combined timelines at 12.5ms spacing. `.speed(2.0)` divides those intervals but cannot consume two poses in one tick, so Kyle saw unchanged working pace while the plugin's shortened timers risked cutting clips off.

A read-only probe against the installed engine (`SpeedProbe.java` under dev integration-tools) reproduced the limit. For nominal 1.8s inputs, 12.5ms/144 intervals took 147 engine ticks at both speeds; 25ms/72 intervals took 75 at both; 50ms/36 intervals took 75 at 1x and 39 at 2x. This is a confirmed timing limitation for these streams, not evidence that the public speed method is absent.

Kyle explicitly authorized the alternative **runtime resampling**, implemented in `GroundworkerPlayback`:

1. Read the currently deployed model source without writing it. The imported blueprint cannot supply the original clock because those dense deltas were already rounded.
2. Retain existing loaded bone identities; use BetterModel's public format-version coordinate conversions and interpolation utilities.
3. Sample source motion at 25ms of playback time, advancing source time by the clip's effective speed. Preserve start/end poses, explicit accessory animators, and step discontinuities.
4. Supply the in-memory `BlueprintAnimation` with modifier speed **1**, since its clock is already scaled. Do not multiply again.
5. Cache prepared clips per renderer; use per-play completion markers across keyed bones. No model-file edits or third-party jar patching.

Nonnumeric/Molang keys, multi-point keys, effects animators and unsupported interpolation types fail visibly rather than silently changing the asset. The current model uses supported numeric linear/step channels. Faster playback necessarily fits fewer intermediate samples into each second. Kyle tested and accepted the resulting working motion. The adapter is version-specific; review/retest it before any future BetterModel upgrade.

### Floating accessory finding

The temporary rest-key comparison fixed floating in Kyle's actual Montgomery job. **The workaround is client-confirmed; the precise upstream root cause remains unproven.** Source inspection found an empty-animator fallback and parent transform composition, so the blanket claim “unkeyed bones never inherit” was not established. Child dirty/update cadence was a plausible factor. Do not patch BetterModel or claim the source investigation conclusively proved the hypothesis. The three explicit rest animators are the successful dev workaround now included in the official asset update.

## Java/Bedrock and resource pack

Java viewers receive the custom model. **Kyle's Bedrock test disproved the former `viewFilter` fallback assumption:** BetterModel still marked Floodgate viewers as spawned and hid the base villager through per-viewer metadata. The fix cancels `ModelSpawnAtPlayerEvent` for Floodgate viewers of HoEL-owned Groundworker trackers, before spawn registration/hiding. Java remains unchanged. See [the visibility diagnosis and fix](Groundworker-Bedrock-visibility-fix.md). Kyle has now confirmed the plain villager is visible on Bedrock. His follow-up showed a duplicate name; the raised TextDisplay is now Java-only, while Bedrock keeps the native villager name. Full build: 86 tests passed. Kyle confirmed Java is unchanged and Bedrock has one nameplate with both clients viewing Montgomery together. GeyserModelEngine remains out of scope.

The dev-only pack merges the existing HoEL GUI v0.4.0 pack with BetterModel's generated `plugins\BetterModel\build.zip`. It retains the GUI assets and BetterModel modern overlay, contains 62 files, and is served from:

`http://192.168.1.52:8765/HoEL-Dev-Groundworker.zip`

File: `D:\Projects\House of EL\Local Dev Server 26.1.2\dev-resource-pack\HoEL-Dev-Groundworker.zip`

SHA-1: `767ddcab6af637f1c0f1c0afd2181dea8f74bef1`

Dev `server.properties` uses this URL/hash and requires the pack. Python HTTP hosting serves only `dev-resource-pack`; `start-headless.ps1` starts it if port 8765 is unused. Supporting `prepare_pack.py` and `serve_pack.py` live under `D:\Projects\House of EL\Local Dev Server 26.1.2\integration-tools\groundworker`. Rerun pack preparation after a future asset/BetterModel pack regeneration, then restart dev. Runtime speed changes did not require a new pack.

An earlier GUI schema v2/v3 disconnect was traced to the wrong client profile; do not disable the GUI version check to mask it. Use the current Voxyris-Dev-compatible profile/pack.

For later main-pack integration, merge generated BetterModel assets and `bettermodel_modern/` into the current HoEL pack, merge overlay metadata in `pack.mcmeta`, and review collisions rather than overwrite them. Preserve generated namespaces/item mappings, test Java, then compute the published SHA-1. **No live pack release is authorized by this report.** Bedrock conversion and remote/LAN reachability checks remain separate work.

## Source and supporting files

All source changes below are in `D:\Projects\House of EL\Plugin-Source`:

- `HoEL-Builder\build.gradle.kts`: BetterModel compile-only API dependency.
- `HoEL-Builder\src\main\resources\plugin.yml`: optional dependency, visual preview command and explicit test permission (default false).
- `HoEL-Builder\src\main\resources\config.yml`: disabled-by-default custom visuals and speed/nameplate/debug settings. The speed comment explicitly exempts greeting and celebration clips; shipped speed is 2.0 and enabled remains false.
- `HoEL-Builder\src\main\java\com\houseofel\builder\visual\HelperVisuals.java`: optional visual hooks / no-op implementation.
- Same visual package: `GroundworkerModelService.java` (Citizens lifecycle/tracker/nameplate/filter/preview), `GroundworkerAnimation.java` (session FSM, coalescing, timers and greeting/celebration speed exceptions), `GroundworkerPlayback.java` (read-only runtime adapter/completion markers).
- `HoEL-Builder\src\main\java\com\houseofel\builder\HoELBuilder.java`: feature flag, optional dependency bootstrap and shutdown.
- `HoEL-Builder\src\main\java\com\houseofel\builder\npc\HelperLevelService.java`: visual level-up hook, XP behavior unchanged.
- `HoEL-Builder\src\main\java\com\houseofel\builder\job\JobManager.java`: optional visual hook wiring.
- Same job package: `ClearJobTask.java`, `QuarrymanJobTask.java`, `LandscaperJobTask.java`, `CofferdamJobTask.java`, `ShaftMinerJobTask.java`: placement notifications, gameplay/pathing unchanged.
- `HoEL-Builder\src\test\java\com\houseofel\builder\visual\GroundworkerAnimationTest.java` and `GroundworkerPlaybackTest.java`: controller and actual engine timing/regression coverage.
- `docs\Groundworker-BetterModel-dev.md`: original integration detail; portions are historical.
- `docs\Groundworker-rest-keys-speed-test.md`: diagnostic chronology, native speed limitation, authorized workaround and pacing exception.
- `docs\Groundworker-CLAUDE-CODE-HANDOFF.md`: this consolidated current-state report.

Dev-only changes include BetterModel jar/config/model/generated pack, deployed Builder jar/config, server resource-pack settings, local HTTP hosting/startup wiring and integration-tools scripts/backups/logs. Final V1 was preserved during diagnostics, then replaced by Kyle’s verified official rest-key update on 5 October with the previous snapshot backed up. Daily notes are in `D:\Projects\House of EL\Obsidian\Archives\House of EL - Archives\Plugin\Daily Summaries\Daily Plugin Summary — 2026-10.md`.

## Build, deployment and acceptance evidence

Historical 5 October deployed Builder (current evidence is in [the 6 October report](Pre-release-2026-10-06.md)):

`D:\Projects\House of EL\Local Dev Server 26.1.2\plugins\HoEL-Builder-0.1.0-SNAPSHOT.jar`

SHA-256: `e09c470cccc698b6a882249211f3faad5e6f9cec2d16087101aa3225eb6c4ef3`

It matches the tested jar in `D:\Projects\House of EL\Plugin-Source\HoEL-Builder\build\libs`. Earlier hashes in previous reports refer to superseded builds.

- Full rebuild before the adapter deployment passed 81 tests; the final level-up exemption build (`.\gradlew.bat build --console=plain`) passed **82 Builder tests**, zero failures/errors/skips, including the real deployed-model probe.
- Portable regressions test once-per-session draw/stow, hold release, Rusted/moving baseline, placement bursts, level-up/recovery order and snap, return to ongoing work, scaled/fractional timers, rendered completion gating, real dense-frame engine speed, multi-bone completion and equivalent-angle step preservation.
- The optional real-model probe reads `GROUNDWORKER_TEST_MODEL`. Without that environment variable it skips; the portable synthetic tests still run. To repeat the exact local verification set it to the deployed `.bbmodel` before Gradle. It reads model bytes and checks they remain unchanged.
- Renderer tests verified 0.9s dig, 1.75s draw/stow, and unchanged 4.5s/5.5s celebration/recovery under a 2x config (excluding fixed engine startup).
- Dev was stopped gracefully through RCON after confirming zero players, jar backed up/copied, and restarted with Java 25. The previous celebration-exemption boot reached Done at 20:34:34 on 4 October; the latest official-asset deployment reached Done at **14:06:18 on 5 October (Asia/Manila)**. All six existing Groundworkers (#7–#12) attached with both roots / 17 bones.
- Startup explicitly reported `dig=0.9s; levelup=4.5s; recover=5.5s`. RCON reported `speed=2.0x; levelup=1.0x; runtime-resampled`. No ERROR/Exception entries were found. Existing unrelated warnings include example MythicMobs dialog skills, ViaVersion updates, Java Unsafe deprecation and Paper's newer-version notice.
- Both source/deployed model hashes and the pack hash were rechecked for this handoff. Models remained unchanged during runtime-speed implementation and celebration exemption.
- **Client evidence:** Kyle confirmed accessories no longer float; accepted the faster working motion; asked to restore celebration pacing; then accepted the final result as perfect. This confirms that specific Java feedback, not an exhaustive Bedrock/network/all-job acceptance matrix.

The official asset promotion includes a fresh build/test run and graceful dev restart; see the latest verification below.

## Useful commands and follow-up

In Voxyris-Dev, use `/helpermodel status`, then `/helpermodel play <npcId> <clip>`. For example `/helpermodel play 8 levelup` previews the entire celebration/recovery sequence without changing level/gameplay. Use an ID returned by status. A player's permission must be granted explicitly: console `lp user <name> permission set houseofel.builder.modeltest true`; OP alone does not grant it. `attached=0` with unloaded NPC chunks/no nearby players is expected.

Build from the repo with `.\gradlew.bat build --console=plain`. Use Java 25 for the dev server. For any later deployment follow `CLAUDE.md`: check players, wait for an empty window, gracefully stop/save, copy the built jar, restart and verify hashes/boot logs; copying a jar alone does not load it. Prefer the established `rcon.py` graceful stop over killing an active job/server.

Remaining work, not a reason to change the accepted motion:

1. Official accessory-rest-key promotion is complete. Preserve the new Final V1 hash and byte-identical dev deployment; no further pose or timing edit is pending.
2. Bedrock fallback and simultaneous Java/Bedrock viewing passed Kyle's client tests. Custom Bedrock model work waits for GeyserModelEngine target-version support.
3. Broader lifecycle/job/placement-burst checks and another-machine pack access can be performed as regression testing; do not characterize all of them as already client-verified.
4. Any commit/push/merge/live release requires its own authorized scope; this handoff is a report, not release approval.

**Next:** preserve the client-accepted integration and official asset; any push, merge, or live release needs separate authorization.


## Official asset promotion and shipped default — completed 5 October 2026

Verified the submitted SHA-256 and semantically compared against both known snapshots. Removing only the added accessory rest-key arrays (and their necessary containers in clips where absent) reconstructs the entire prior approved JSON. All 120 keys are zero position/rotation at start/end across ten clips, linear, plain decimals. The official and experimental copies differ only in added-key UUIDs/order, not motion or any other model data.

Backups in Final V1: `groundworker_v1_before_restkeys_20261005_140248.bbmodel` (old approved hash `3292f15168838738c2590f9f155c1104f69024e369c354879eb4134fe9c7fea5`) and `CLAUDE_CODE_HANDOFF_before_restkeys_20261005_140248.md`. The official candidate was renamed to `groundworker_v1.bbmodel`; the handoff now records its new hash and client-confirmed floating fix.

Shipped YAML now has `animation-speed: 2.0`, `enabled: false`, and a corrected celebration-exemption comment. Missing-setting fallback in the visual service is also 2.0; invalid-value fallback stays 1.0. The pure controller's explicit 1x constructor/test cases still exercise 1x behavior; they are not the shipped config default. Both celebration clips remain exempt at 1x.

Full `gradlew.bat build --rerun-tasks --console=plain` passed all **82 tests**, zero failures/errors/skips, with `GROUNDWORKER_TEST_MODEL` pointing at the new official Final V1. The built jar's embedded config was checked. Model and Builder jar were deployed as byte copies, and their post-startup hashes match the tested/official files.

Dev was already stopped (no Paper process and RCON refused connection), so no running server or player was interrupted. Existing dev model, jar and generated pack were backed up under integration-tools/groundworker before copying. Started dev with Java 25 and its local pack host. Startup reached Done at 14:06:18 Asia/Manila; all six Groundworkers attached, no ERROR/Exception entries. RCON confirms `speed=2.0x; levelup=1.0x; runtime-resampled`; logs show dig=0.9s, levelup=4.5s, recover=5.5s.

BetterModel’s startup pack generation retained the existing `build.zip`: its `ZipGenerator` skips rewriting when the generated resource hash is unchanged. The ZIP timestamp stayed unchanged and entry contents are identical to the pre-promotion pack, and every generated asset matches the existing merged dev pack; no merged-pack regeneration, hash change or second restart was needed. Pack SHA-1 remains `767ddcab6af637f1c0f1c0afd2181dea8f74bef1`.

Official Final V1 and deployed model SHA-256: `da84343c3abbf8ea40b48187f04b99f32d8d741a7cdf0fd3aedadea6a0ef268b`.

Built/deployed Builder SHA-256: `3e89111c7dd6ef583ad98cc736bde4e9327833226c5dc6c1d980a59bdeaefa5c`.


## Bedrock visibility correction — 5 October 2026

The old viewer filter failed Kyle’s Bedrock test and has been replaced by per-player spawn cancellation. See [the diagnosis, source paths, tests and client matrix](Groundworker-Bedrock-visibility-fix.md). Full build: 85 tests passed. The models, pack and accepted pacing remain unchanged. Earlier claims of an already-visible Bedrock fallback were incorrect.


Nameplate follow-up (5 October): raised labels are hidden by default and shown only to Java, including late joins and world changes. Dev Builder SHA-256: `436939470223629b6468aa6d42654a760dde638b23dae1e54855f95f6c5e532a`. See the visibility report for deployment and verification details.
