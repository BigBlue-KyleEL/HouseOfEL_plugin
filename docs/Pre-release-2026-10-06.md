# First live deploy: pre-release implementation — 6 October 2026

> **Note:** "HoEL" was renamed to "HEL" on 2026-10-07. Paths and names below reflect the pre-rename state.

> **Later 6 October build:** fork screens are now implemented and locally deployed; see [the fork GUI report](Fork-GUI-2026-10-06.md) for the current Core/Builder hashes, 122-test result and acceptance checklist. This report’s earlier 100-test deployment remains historical evidence. Both sets of changes remain uncommitted.

Implemented on `dev` at base `18e88af`; **uncommitted, awaiting Kyle’s in-game acceptance**. `main` remains `a4755a7`. No merge, push, release or live-server access occurred. Specification: vault `Plugin/Planning/House of EL — First Live Deploy Plan (2026-10-06).md`, pre-release section. This report supersedes older 2x greeting timings.

## Behavior and configuration

In `plugins/HoEL-Builder/config.yml`:

```yaml
helpers:
  diagnostics:
    enabled: false
```

Missing key also means false, so existing configs are quiet by default. This gates the server-wide sponge-absorption listener and all four `[cofferdam-debug]` logging sites. It does not alter sponge events, job results or normal warnings/errors. Set true for troubleshooting and **gracefully restart the server**; changing the file does not hot-reload it. Restore false and restart afterward. Do not use Bukkit `/reload`. The separate `helpers.custom-model.debug` setting is unchanged (shipped false, current dev true).

`GroundworkerAnimation.speedFor` now exempts `greet` alongside `levelup` and `levelup_recover`. Runtime resampling, controller deadlines and previews already share this function. At configured speed 2.0: greet is 2.1 seconds / 42 ticks, levelup 4.5 seconds / 90 ticks, recovery 5.5 seconds / 110 ticks; dig remains 0.9 seconds, draw/stow 1.75 seconds. Final-pose completion and the one-tick display guard remain, so visible cadence can exceed nominal motion duration slightly. Model files and pack bytes are unchanged. Shipped model flag remains false, speed 2.0; dev model stays enabled.

| Command | Explicit permission (default false) | Console |
|---|---|---|
| `/builder setlevel <npcId> <level>` | `houseofel.builder.setlevel` | Local console and RCON permitted |
| `/builder testpanel` | `houseofel.builder.testpanel` | Player-only GUI |

OP status alone does not bypass the gates; explicit permission grants apply. Tab completion respects each gate independently. Existing testmenu/testbusy/modeltest gates remain. Local LuckPerms grants were added directly to Kyle’s verified Java `BigBlue0527` and Bedrock `.KyleEL9084` identities only. Before/after exports verify exactly these two new nodes per account, no removed nodes, and identical groups/tracks. No Sub-Admin grant. Live grants remain a later deployment step using the verified live UUIDs, e.g. `lp user <Kyle UUID> permission set houseofel.builder.setlevel true` and the matching testpanel command.

## Testing-settings audit

- `TEST_SPEED_MULTIPLIER` absent from current source.
- `HelperTempo.WORK_PACE_MULTIPLIER=5.0` unchanged: **accepted for launch — may be tuned down later if it feels too fast live**.
- `ClearJobTask.TEST_DROP_MULTIPLIER=1`; no inflated drops.
- `LedgerExpiryTask.WINDOW_MILLIS=REAL_WINDOW_MILLIS`: seven real days. Historical accelerated-expiry comment does not change the active value.
- Normal `isAtCeiling()` checks remain in job dispatch and NPC/menu paths. Groundworker tickets remain 512 blocks.
- Landscaper `PLACE_DELAY_TICKS=0` is intentional; preserved. Cofferdam placement delay remains 3 ticks.
- Shipped diagnostics/model debug/model enabled default false; animation speed 2.0. Dev visual debugging remains a separate deliberate dev override.
- **Deferred existing setting:** `RegionSelectionService.TIMEOUT_ENABLED=false`, dormant duration 20 seconds. August Daily Summary records this was disabled because 20 seconds was too short to inspect large areas. No timeout policy was invented or changed during this pre-release task. A future duration/re-enable decision remains open; include this in launch review rather than claiming all testing markers were removed.

## Validation

Final full run: `.\gradlew.bat build --rerun-tasks --console=plain`, JDK 25.0.4.7, pinned Paper API 26.1.2, `GROUNDWORKER_TEST_MODEL` set to the approved Final V1. **BUILD SUCCESSFUL: 100 tests, zero failures/errors/skips.** All modules built; modules without tests reported NO-SOURCE. Existing deprecated API / Gradle 10 compatibility notices remain.

Regressions cover missing/false/true diagnostics, actual sponge listener and Cofferdam scan logging/results, independent permission gates including denied OP, allowed panel dispatch, permitted level-command validation, local/RCON console routes, packaged defaults, greeting playback and preview deadlines/final-pose guard. The official-model probe actually ran: greet 2100ms at both configured 1x and 2x, levelup 4500ms, recovery 5500ms; dig 1800→900ms. `git diff --check` passed. Packaged shaded jar includes SQLite and the new config/permission declarations.

Local smoke testing found and corrected Paper’s separate RCON sender type before final deployment. Final RCON `builder setlevel bad-id 20` returns usage, proving the console route reaches input validation. No Helper level was changed for this check. Visual acceptance and successful player-side level mutation still need Kyle’s in-game check on a disposable/test Helper.

## Local deployment evidence

Target only: `D:\Projects\House of EL\Local Dev Server 26.1.2`. Only the changed shaded Builder jar was replaced; other plugin/client jars, models and resource pack were not replaced.

- Original jar/config/log backup: `integration-tools/pre-release-20261006-155025/` under the local dev server. Also contains the offline LuckPerms database before grants. Permission exports: `plugins/LuckPerms/pre-release-before-20261006.json.gz` and `pre-release-after-20261006.json.gz`.
- First deployment found Paper stopped. After the RCON correction, checked zero online players, used RCON `stop`, verified process exit and completed world saves, copied the final jar, and started Java hidden with the existing headless launcher. No forced kill.
- Final startup completed **2026-10-06 15:53:18 Asia/Manila**, Paper 26.1.2, BetterModel 3.0.2. Builder enabled; all six Groundworker attachments logged; saved job resumed. Later status has one attached NPC as empty-server chunks unload.
- Startup reports `dig=0.9s; greet=2.1s; levelup=4.5s; recover=5.5s`; RCON status reports the three 1.0x exceptions. No ERROR/Exception entries in inspected startup log. Existing plugin-update and MythicMobs missing-example-skill warnings remain.
- Built and deployed Builder SHA-256: `d5d95c935d408b68518d8e1c9704ae072a74dc3ef7ab4de4ef09f61947017d7d`.
- Authoritative and deployed model SHA-256, unchanged: `da84343c3abbf8ea40b48187f04b99f32d8d741a7cdf0fd3aedadea6a0ef268b`.
- Dev merged pack SHA-256, unchanged: `a9da5db8ed48e2797da3dbc464278a4332654f8b688a897d32ffb8b6f194fd82`.

## Files changed

All paths below are relative to Plugin-Source:

| Area | Files |
|---|---|
| Command gates | `HoEL-Builder/src/main/java/com/houseofel/builder/command/BuilderCommand.java`; `HoEL-Builder/src/main/resources/plugin.yml` |
| Diagnostics | `HoEL-Builder/src/main/java/com/houseofel/builder/job/JobDiagnostics.java` (new), `SpongeAbsorptionDiagnosticListener.java`, `CofferdamJobTask.java` in the same job package; `HoEL-Builder/src/main/resources/config.yml` |
| Greeting | `HoEL-Builder/src/main/java/com/houseofel/builder/visual/GroundworkerAnimation.java`, `GroundworkerModelService.java` |
| Tests | `HoEL-Builder/src/test/java/com/houseofel/builder/command/BuilderCommandPermissionTest.java` (new); `job/JobDiagnosticsTest.java` (new), `visual/GroundworkerAnimationTest.java`, `visual/GroundworkerPlaybackTest.java` under the same builder test package |
| Docs | This report; `docs/Groundworker-CLAUDE-CODE-HANDOFF.md`, `docs/Groundworker-BetterModel-dev.md`, `docs/Groundworker-rest-keys-speed-test.md` |

Outside Git: local Builder jar/config and Kyle-only LuckPerms grants/backups; vault Visual Design, deploy plan, Timeline and October Daily Summary updated with current status. Masterfile’s existing plan link remains valid.

## Kyle’s in-game check / next

1. Join Voxyris-Dev with the current GUI schema-v3 profile. Right-click a Groundworker: greeting should take about 2.1 seconds, finish cleanly and retain the existing menu interaction. Also try `/helpermodel play <npcId> greet` from the Java account to check preview pacing.
2. Run/resume one ordinary job: work stays at the accepted faster pace, with one draw/stow per session. Preview/check levelup and recovery at original pace and preserve the intentional recovery snap; verify accessories stay attached.
3. With Java and Bedrock together, Java still sees the model/raised label; Bedrock sees the native villager and one nameplate.
4. Kyle can open `/builder testpanel` (Java GUI test) and use `/builder setlevel` on a chosen test Helper. An account without the nodes should be denied both commands. Avoid changing a valued Helper’s progression just to test access. Automated denied/permitted-route coverage already passed.
5. Normal sponge/Cofferdam work should not emit the two diagnostic log families with the switch off. Automated on/off coverage passed. If a real diagnostic comparison is needed, arrange a graceful dev restart with the switch true, compare logs, then restore false and restart.

**Next:** Kyle checks the local build and reports acceptance/issues. Do not commit until that check. Any later dev commit still precedes an independently authorized main merge/release/live deploy.
