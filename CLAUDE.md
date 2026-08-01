# House of EL — Plugin Source

Custom Minecraft **Paper** plugin suite for a small private family server (Java/Bedrock crossplay via Geyser+Floodgate). This repo holds only plugin/script source — world data, 3D assets, and the design docs (Obsidian vault) live outside it on purpose.

**Source of truth for design decisions:** the Obsidian vault, specifically `House of EL — Plugin Design Document.md` (the Masterfile). This file is for repo/build mechanics only — don't duplicate design content here.

## Structure

Gradle multi-module project, 4 modules:
- `HoEL-Core` — base module, owns the `/hel` command + shared services
- `HoEL-Builder` — Helper NPC (builder/gatherer companion) systems
- `HoEL-Encounters` — combat/encounter systems (MythicMobs bridging)
- `HoEL-LLM` — Gemini API bridge for lore/quest NPC dialogue

All shared build config (repositories, Paper API dependency, Java toolchain) lives in the **root** `build.gradle.kts` via a `subprojects { }` block. Each module's own `build.gradle.kts` is intentionally near-empty — don't add per-module repository/dependency config unless a module genuinely needs something the others don't.

## Commands

```
.\gradlew.bat build      # compile + build all 4 module jars
.\gradlew.bat clean build # full rebuild
```

Built jars land in `<Module>/build/libs/<Module>-0.1.0-SNAPSHOT.jar`.

## Current target version — read before touching build.gradle.kts

Paper API target is currently pinned to **26.1.2**, not the latest 26.2. This is deliberate, not stale: a client-side visual mod (Voxy Server Side / LOD Server Support, used for testing distant terrain rendering) doesn't yet support 26.2. If bumping this, confirm the local dev server and that mod's compatibility first — don't just chase the newest Paper release.

## Local testing

Two local server folders exist outside this repo:
- `D:\Projects\House of EL\Local Dev Server 26.1.2` — **active**, matches this repo's current target
- `D:\Projects\House of EL\Local Dev Server` — 26.2, superseded, not in sync with current builds

To test a build: copy the jar(s) from `build/libs/` into `Local Dev Server 26.1.2\plugins\`, then boot with:
```
cd "D:\Projects\House of EL\Local Dev Server 26.1.2"
& 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot\bin\java.exe' -jar paper-26.1.2-74.jar --nogui
```
Requires JDK 25 specifically (installed alongside the system's existing Java 21 — don't assume `java` on PATH resolves to 25).

## Code conventions

- Package root: `com.houseofel.<module>` (e.g. `com.houseofel.core`)
- `plugin.yml` (legacy Bukkit format, not `paper-plugin.yml`) with `api-version: '1.13'` — this is a stable low baseline, not a stale value; don't "fix" it to match the current MC version
- Gate meaningful commands behind real LuckPerms permission nodes with `default: false` in plugin.yml (not `default: op`) — OP status should not silently bypass permission checks. See `HoEL-Core`'s `/hel` command for the pattern.

## Known gotcha

In the root `build.gradle.kts`, the Java toolchain block must use `configure<JavaPluginExtension> { }`, not the `java { }` shorthand — the shorthand fails to resolve because the `java` plugin is applied imperatively inside `subprojects { }`, not via a static `plugins { }` block. This already cost a debugging round once; don't revert it.

## Repo etiquette

- Remote: `github.com/BigBlue-KyleEL/HouseOfEL_plugin`, branch `main`
- `.gitignore` already excludes `build/`, `.gradle/` — verify `git status` looks clean (no build artifacts staged) before committing
