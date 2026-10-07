# Groundworker: temporary rest-key experiment and 2x speed test

> **6 October pre-release policy:** `greet` joins `levelup` and `levelup_recover` at 1.0x; other clips use the configured 2.0x. This supersedes the historical accelerated-greeting timings below. No model or pack change. See [the implementation report](Pre-release-2026-10-06.md); in-game acceptance is pending.

> **Current accepted state:** see [the consolidated Claude Code handoff](Groundworker-CLAUDE-CODE-HANDOFF.md). It supersedes historical hashes/timings and pending Java feedback below. Kyle accepted the final working speed, original level-up pacing and accessory fix.
> **5 October update:** official rest-key model promoted and byte-identically deployed (`da84343c…268b`); experiment is historical. Shipped speed default is now 2.0, feature remains disabled by default, and levelup/recovery stay exempt at 1.0x. The consolidated handoff contains backup names, verification and deployment details.

2026-10-04; branch `dev`; **Local Dev Server 26.1.2 only**. No Final V1 edit, head tag, re-parenting, geometry change, plugin upgrade, gameplay timing change, main merge, or pack release.

## Floating-parts investigation

Confirmed on the approved source: `headband`, `apron`, and `shovel_sash` each have **zero keyframes in all ten clips**. Most clips contain empty animator entries for them; `rusted_idle` and `levelup_recover` omit them. The unused `held_item` remains untouched.

Inspected the installed BetterModel **3.0.2** source:

- [`ModelAnimation.toBlueprint`](https://github.com/toxicity188/BetterModel/blob/3.0.2/api/src/main/java/kr/toxicity/model/api/data/raw/ModelAnimation.java) filters available animators and builds a fallback empty animation timeline from the first generated animator's frame times.
- [`RenderedBone.addAnimation`](https://github.com/toxicity188/BetterModel/blob/3.0.2/api/src/main/java/kr/toxicity/model/api/bone/RenderedBone.java) uses that empty iterator when a bone has no animator, except when override rules explicitly skip it. `BoneStateHandler.after()` combines the local/rest movement with the parent's position, scale, and rotation. `tick()` marks transforms dirty when its state advances, and `sendTransformation()` sends only marked updates.
- [`AnimationGenerator.createMovements`](https://github.com/toxicity188/BetterModel/blob/3.0.2/api/src/main/java/kr/toxicity/model/api/data/blueprint/AnimationGenerator.java) generates explicit animators from the provided point map and aligns animation sampling to a shared time set. It does not add an explicit keyed animator for every child automatically.

**Conclusion:** the source does not support the blanket claim that an un-keyed child never inherits its parent's animation. There is an empty-animator path and parent-transform composition. It does leave the child's updates dependent on its own state/dirty cadence, so the requested explicit-rest-key comparison is a useful diagnostic. A root cause is **not confirmed** from source inspection alone.

The official animation [wiki](https://github.com/toxicity188/BetterModel/wiki/Animating-your-own-model) describes built-in clips and testing, but does not prescribe constant rest keys for accessory children. Upstream [issue #403](https://github.com/toxicity188/BetterModel/issues/403) is open and reports child parts appearing detached/lagging behind their parent, including an armor piece on a head. Its reported environment differs from this dev server; it supports a matching symptom, not proof of the same cause.

## Temporary model currently deployed

Generated with Python under `Local Dev Server 26.1.2/integration-tools/groundworker/generate_rest_key_experiment.py`.

- Experiment copy: `integration-tools/groundworker/groundworker_rest_keys_experiment.bbmodel`.
- Active dev file: `plugins/BetterModel/models/groundworker.bbmodel`.
- Original deployed bytes saved to `integration-tools/groundworker/groundworker_before_rest_keys_experiment.bbmodel`.
- Added **120 keyframes**: three bones x two channels (position and rotation) x two times (0 and clip end) x ten clips. Every vector is `0,0,0`, interpolation `linear`, numeric values are plain decimals, no exponent notation.
- Deep-comparison validation removes only the three added animator changes and recovers the original parsed model exactly. All authored keys, clip lengths/loop modes, UUIDs, geometry, hierarchy, head name, texture, and remaining data are preserved.
- Approved Final V1 remains SHA-256 `3292f15168838738c2590f9f155c1104f69024e369c354879eb4134fe9c7fea5`.
- Current temporary copy SHA-256 `030c1b7891dc030662da53ef47cd61e7bc627dfe870ee6ac197b762e1e073ad7`.

**Observed result:** the temporary copy loads successfully and six Groundworkers attach after restart. **Kyle’s Java test confirmed the headband and apron/sash straps no longer float.** The temporary-rest-key workaround is confirmed by client observation; the exact upstream cause remains unproven. Stop here on model changes and wait for Kyle’s official model update. Stop after this experiment if it works and let Kyle produce the official model update. If it still floats, investigate and report before any further change.

To restore the original later, copy `Final V1/groundworker_v1.bbmodel` directly over the active dev file (or use the byte-identical backup), run `bm reload`, regenerate the local pack with `prepare_pack.py`, and restart the empty dev server. This is a diagnostic copy, not an approved replacement asset.

## Animation-speed configuration

Added `helpers.custom-model.animation-speed` with an initial shipped default of 1.0; **superseded on 5 October: shipped default is 2.0**, feature remains disabled by default, celebration clips remain exempt. Dev config is 2.0. Restart Builder/server after changing it. All named clips receive the same multiplier through BetterModel's supported [`AnimationModifier.Builder.speed(float)`](https://github.com/toxicity188/BetterModel/blob/3.0.2/api/src/main/java/kr/toxicity/model/api/animation/AnimationModifier.java). Its [`AnimationStateHandler`](https://github.com/toxicity188/BetterModel/blob/3.0.2/api/src/main/java/kr/toxicity/model/api/animation/AnimationStateHandler.java) divides keyframe time by the modifier's speed value.

The controller uses the **same float multiplier** and rounds each duration upward to a server tick. This avoids cutting a clip short at fractional multipliers. There is a minimum one-tick boundary/intentional idle guard. Non-positive or non-finite configured values log a warning and fall back to 1.0. No model key times or gameplay/job timers were sped up.

| Clip/timer | Base duration at 1x | Dev duration at 2x |
|---|---:|---:|
| shovel_draw | 70 ticks / 3.5 s | 35 ticks / 1.75 s |
| shovel_stow | 70 ticks / 3.5 s | 35 ticks / 1.75 s |
| place | 24 ticks / 1.2 s | 12 ticks / 0.6 s |
| greet | 42 ticks / 2.1 s | 21 ticks / 1.05 s |
| levelup | 90 ticks / 4.5 s | 45 ticks / 2.25 s |
| levelup_recover | 110 ticks / 5.5 s | 55 ticks / 2.75 s |
| Loop preview window | 100 ticks / 5 s | 50 ticks / 2.5 s |

`idle`, `walk`, `dig`, and `rusted_idle` loops also receive 2.0 via BetterModel, so their cycles halve in duration. Session draw/stow semantics, placement coalescing, Rusted behavior, no head-follow, and the intentional recovery snap remain unchanged. Maintenance scans and Rusted polling retain their normal cadence because they are not clip timers.

## Build, deployment, and checks

- `gradlew.bat build --rerun-tasks --console=plain`: **passed**, all tasks executed, **76 Builder tests**, zero failures. Existing 1x tests still pass. New tests exercise every timed clip at 2x, loop-preview timing, fractional rounding, and invalid speed values.
- Built/deployed Builder SHA-256: `f883f7d3bcda2deb54cece970715e35dfb5673e291d4391e95418f4cb9752752`.
- Dev restarted after confirming zero online players. Startup loaded the updated Builder and BetterModel, retained 17 bones, and attached all six existing Groundworkers (#7–#12). No ERROR/Exception entries.
- `/helpermodel status` confirms **speed=2.0x**. `attached=0` is expected with nobody near unloaded NPC chunks.
- Regenerated/merged local pack remains 62 files. Dev pack SHA-1: `767ddcab6af637f1c0f1c0afd2181dea8f74bef1`. URL remains `http://192.168.1.52:8765/HoEL-Dev-Groundworker.zip`.

Changed source: `GroundworkerAnimation.java`, `GroundworkerModelService.java`, shipped `config.yml`, and `GroundworkerAnimationTest.java`. Also updated this report and the integration report's current-test pointer. Dev-only changes: configuration speed, temporary model/script/backup, generated local pack and hash, deployed jar, and restart logs.

## Java observation needed

Join Voxyris-Dev, accept the current dev pack, and repeat Montgomery's real job. Watch the headband while the head animates and the apron/sash straps while the body animates, especially draw → dig, travel, placement, and stow. State whether they **stay attached** or **still float**. Do not promote this copy to Final V1.

Use `/helpermodel status` to confirm 2.0x and `/helpermodel play <id> levelup` to check the 2.25s → 2.75s sequence and intentional snap. Draw/stow each take 1.75s; greet takes 1.05s; place takes 0.6s. A console grant of `houseofel.builder.modeltest` is required for a player's visual-preview command, as before. Attachment is confirmed by Kyle’s client test. Rendered speed failed that test; see the runtime probe below. Controller tests do not validate BetterModel’s renderer cadence.


## Follow-up: floating fixed; speed modifier limited by dense frames

Kyle confirmed accessories stay attached but animations appeared to run at the same pace. A read-only probe against the **installed BetterModel jar** reproduced why:

- Scheduler advances once every 25 ms (`Tracker.TRACKER_TICK_INTERVAL`).
- Importer `InterpolationUtil.roundTime` rounds 12.5 ms frame differences down to zero.
- `AnimationStateHandler.tick()` consumes at most one keyframe per scheduler tick. Dividing a zero/25 ms interval by 2 still cannot advance two poses within one tick.
- Most approved clips contain a combined keyframe timeline at 12.5 ms spacing. The speed API exists, but cannot produce 2x playback of these dense clips.

`integration-tools/groundworker/SpeedProbe.java` uses the actual installed `AnimationStateHandler`, `AnimationModifier`, and `InterpolationUtil`; it does not patch the engine or model.

| Input frame gap, nominal 1.8 s | Engine ticks at 1x | Engine ticks at 2x |
|---|---:|---:|
| 12.5 ms / 144 intervals | 147 | 147 |
| 25 ms / 72 intervals | 75 | 75 |
| 50 ms / 36 intervals | 75 | 39 |

This supersedes the earlier implication that calling `.speed(2.0)` guarantees doubled rendering. The current controller deadlines do halve, but the dense rendered clips do not; **a one-shot may be interrupted before its rendered motion finishes**. The earlier 76 passing tests verify controller timing, not renderer timing.

No further model edit or runtime workaround was applied during this investigation. Final V1 and the working temporary rest-key copy remain unchanged. Current dev config remains 2.0 pending Kyle’s decision. Real 2x playback needs an alternate runtime strategy, such as resampling the already-loaded animation timeline to the renderer’s 25 ms cadence; that would leave `.bbmodel` files unchanged but use fewer intermediate poses. Kyle asked to be told before trying an alternative if BetterModel speed control could not do this, so approval is pending. Restoring 1.0 can remove the additional shortened-controller mismatch, though the importer’s original dense-frame limitation still exists.


## Authorized runtime resampling — 4 October 2026

Kyle explicitly approved trying runtime resampling after the native speed limitation was reported. `GroundworkerPlayback` reads the currently deployed `.bbmodel` without writing it, retains BetterModel's existing bone identities, converts coordinates through its public format-version API, and samples the authored numeric linear/step channels at 25 ms of playback time. Source sampling advances by `animation-speed` each frame; BetterModel receives the resulting `BlueprintAnimation` with modifier speed **1**, preventing double scaling. The imported timeline cannot be used as the source clock because its 12.5 ms deltas were already rounded to zero. All ten clips use this adapter, including 1x; 2x is relative to authored clip time, not the previously slowed importer playback.

The exact first/last poses, independent roots, explicit accessory rest animators, and recovery's discontinuity are retained. Unsupported nonnumeric expressions, multi-point keys, effect animators or other interpolation modes fail visibly instead of silently rewriting motion. No model is regenerated, reposed, retimed, or written. No head-follow or extra cross-clip blending is added. At faster playback fewer intermediate poses fit the renderer cadence.

Clip deadlines still scale by the same factor and round up to server ticks. Every keyed bone signals application of its final pose; timed clips hold that pose until the controller releases it after one server tick for display interpolation. This prevents the renderer's fixed startup and differing server/renderer clocks from cutting off clips or briefly resetting into the base pose. Levelup transitions directly to recovery upon completion, with no additional animation or smoothing; recovery still snaps into idle intentionally. Actual transitions have small scheduler/interpolation overhead beyond nominal motion durations.

Renderer-level tests using BetterModel 3.0.2's actual `AnimationStateHandler` measured all ten clips, subtracting its fixed two-25ms-tick startup:

| Clip | Motion at 1x | Motion at 2x |
|---|---:|---:|
| idle | 4.0s | 2.0s |
| walk | 1.2s | 0.6s |
| dig | 1.8s | 0.9s |
| rusted_idle | 4.5s | 2.25s |
| shovel_draw / shovel_stow | 3.5s | 1.75s |
| place | 1.2s | 0.6s |
| greet | 2.1s | 1.05s |
| levelup | 4.5s | 2.25s |
| levelup_recover | 5.5s | 2.75s |

`gradlew.bat build --rerun-tasks --console=plain` passed all **81 Builder tests**, with zero skipped tests when `GROUNDWORKER_TEST_MODEL` points to the deployed dev model. New tests cover real dense-frame playback, full completion across bones, the equivalent-angle recovery step, all ten real clips/rest channels and unchanged file bytes, and controller waiting for render completion. The optional real-model probe skips on machines without that environment variable; portable synthetic renderer tests always run.

Changed for this follow-up: `GroundworkerPlayback.java`, `GroundworkerModelService.java`, `GroundworkerAnimation.java`, `GroundworkerPlaybackTest.java`, `GroundworkerAnimationTest.java`, and this/integration documentation. Historical setting at this stage: shipped 1.0 and dev 2.0. The 5 October decision changes the shipped default to 2.0; celebration clips remain exempt. Approved model SHA-256 stays `3292f15168838738c2590f9f155c1104f69024e369c354879eb4134fe9c7fea5`; temporary rest-key model stays `030c1b7891dc030662da53ef47cd61e7bc627dfe870ee6ac197b762e1e073ad7`. Pack contents are unchanged, so no resource-pack regeneration/release is needed.

Builder deployed SHA-256: `e196064dad80b4b3fb677d2fb99d186ceac3e0a05e4823a95581b4a5725ebbb1`. Dev restarted gracefully after checking zero players. Client speed/transition acceptance remains a Java test: `/helpermodel status` must show `speed=2.0x; runtime-resampled`, then `/helpermodel play <id> dig` and `levelup`, and a real Montgomery job. Check faster motion, once-per-session draw/stow, accessories staying attached, recovery without a new turn, and the intentional idle snap. Final V1 still awaits Kyle's official accessory-rest-key update; do not promote the experimental file.


Restart verification: new dev PID 24684, Builder attached all six existing Groundworkers at 20:24:55 host time, and Paper reached Done at the same log time. The log explicitly reports `Groundworker runtime playback prepared at 2.0x, 25ms cadence; model file read only; dig=0.9s`. RCON status confirms `speed=2.0x; runtime-resampled`; unloaded NPC chunks with no nearby players correctly return attached=0. No ERROR/Exception entries were found. Post-restart hashes of both models remain unchanged, and deployed Builder matches the tested jar. Client visual acceptance is not claimed from these headless checks.


## Level-up pacing exemption — Kyle's Java feedback, 4 October 2026

Kyle accepted the faster working motion but asked to preserve the previously approved level-up pacing. Both `levelup` and `levelup_recover` now bypass `helpers.custom-model.animation-speed` and use **1.0x**: authored motion durations **4.5s** and **5.5s**, with original 90/110-server-tick controller deadlines plus the existing final-pose completion guard. Work/other clips continue at configured speed (dev 2.0x). Standalone recovery preview uses the same exception. No keyframes, model, pack, or recovery snap were changed.

The shared speed policy feeds runtime sampling and controller deadlines. Renderer regression tests prove both celebration clips retain 1x duration under a 2x config while all other clips remain doubled. Full Gradle build and all **82 tests** passed with zero failures/skips, including the deployed-model probe. Deployed Builder SHA-256: `c6ea0e61eb1858a0d64bc487e6457c8998f9c68c08dca052022048c59d2fae7f`. Dev restarted gracefully after confirming zero players. Test `/helpermodel play <id> levelup`: original-paced celebration/recovery, intentional idle snap; a real job still digs at the accepted faster pace. Status reports `levelup=1.0x` separately from the general speed.
