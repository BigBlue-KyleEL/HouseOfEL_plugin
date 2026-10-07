# Helper recruitment naming — 6 October 2026

> **Accepted by Kyle — 6 October 2026:** “everything passed” for the fork screens/recruitment fixes and Helper naming. This supersedes the historical pending-acceptance wording below. Completion report in the vault: `Plugin/Planning/House of EL — Fork GUI and Helper Naming Completion Report (2026-10-06).md`. Work remains uncommitted; no commit/release/live action was requested by the report.

Implemented on dev, uncommitted at base `18e88af`. No main merge, push, release or live-server changes. This follows the fork GUI work and preserves its alignment/Coming soon behavior.

## Flow

`/builder spawn` → specialization → **New Helper — Name** → confirm recruitment. This is the new Helper’s specialization picker, not an existing Helper’s job-dispatch menu. Groundworker is available; Lumberjack/Farmer still refuse and reopen the specialization picker.

The name textbox starts with the next unused roster suggestion, which can be kept or replaced. Modded Java uses the existing v3 TextInput and themed Recruit/Cancel buttons. Unmodded Java uses a Paper text-input dialog; Bedrock uses a native text-input form (Submit confirms). No client or schema update.

No resources are charged and no Helper is created at specialization selection or on cancelling the name step. Confirmation validates the submitted name and then invokes the existing recruitment cost/outcome. Invalid names stay in the naming step with a visible error. Insufficient funds use the existing refusal without spawning. Permission and specialization availability are rechecked at confirmation. Per-player requests and one-use screen sessions reject old/repeated submissions.

Names are 1–24 characters, starting with a letter or number; letters (including accented names), numbers, spaces, apostrophes and hyphens are accepted. Leading/trailing spaces are trimmed and repeated spaces collapsed; formatting/control characters are rejected. Base names must be unique among registered Helpers, case-insensitively with Unicode normalization. Availability is checked again immediately before charging. Suggestions also avoid case-insensitive collisions.

The chosen name is stored in Citizens’ existing persistent `houseofel-base-name` field before titles are applied, and immediately saved with the new NPC. No migration or renaming of existing Helpers. Earned titles continue to append to the chosen base name. Chat commands prefer the longest matching base name, so `Ann Marie report` reaches Ann Marie even if Ann also exists.

## Verification

Full `gradlew.bat build --rerun-tasks --console=plain`, JDK 25 / Paper 26.1.2: **130 tests passed, zero failures/errors/skips**. New regressions cover accepted/invalid/duplicate Unicode names, refusal before charges/creation, multiword command matching, v3 textbox round trip, typed/malformed name values, cancellation and duplicate/stale submissions. Prior model and fork GUI tests also pass. `git diff --check` passed; no common/fabric changes.

Local-only deployment: zero players verified, previous Builder/log backed up at `D:\Projects\House of EL\Local Dev Server 26.1.2\integration-tools\helper-naming-20261006-220934`, graceful RCON stop and saved-world/process-exit verification, then shaded Builder replaced and hidden launcher started. Startup complete **22:10:49 Asia/Manila**, no ERROR/Exception entries in inspected startup log; RCON responds normally. Existing external-plugin warnings remain.

Built/deployed Builder SHA-256: `86c9c165157df9bb7f14b7b14f3429c0b750c1b8c72490a45b96d328d14af629`. Model and pack hashes remain unchanged from the fork GUI report. No client/pack/config replacements and no test NPCs created by automated verification.

## Changed files for naming

Within `HoEL-Builder/src/main/java/com/houseofel/builder/`: new `npc/HelperNames.java`, `npc/RecruitmentNameFlow.java`; updated `npc/BuilderNpcService.java`, `npc/SpecializationDialog.java`, `npc/SpecializationForm.java`, `gui/ForkScreenHandler.java`, `gui/ForkScreenLayout.java`, `HoELBuilder.java`. Added `npc/HelperNamesTest.java` and extended the fork layout/handler tests under the matching test package. Existing pre-release changes remain uncommitted.

## In-game acceptance / next

1. On modded Java, `/builder spawn` → Groundworker should open a suggested-name textbox. Cancel first: no charge or NPC. Reopen, replace the suggestion with a unique name and Recruit: exactly one Helper, normal cost, chosen name.
2. Try empty, overlong, formatting-code and existing names: visible error, no charge/spawn; fix the name and retry.
3. Check `<chosen name> report`, `<chosen name> respec` where eligible, earned titles and persistence after a later graceful restart. Try a multiword name. Actual in-game persistence/appearance awaits this check.
4. Repeat naming on unmodded Java (Paper input) and Bedrock (native form). Coming soon specializations should never reach successful creation.

**Next:** Kyle checks the naming step and remaining fork feedback. Resolve issues before any commit. Launch/main/release/live work still waits for acceptance and separate authorization.
