# ArcMenu Editor

**English** · [简体中文](README_zh_CN.md)

ArcMenu Editor is the administrator-side Fabric visual editor shared by [ArcMenu Community](https://github.com/FENTAIIII/ArcMenu) and ArcMenu Premium; it places tools, an element manager, templates and properties around a live 16:9 game viewport backed by the server's real menu.

Click **Image** to show the media library in the bottom panel. Browse folders, preview resource-pack images and their dimensions, then double-click an image or click **Add** to insert it. Internal cursor and tooltip images are hidden. Click **Select** to return to templates. Previews use the loaded server resource pack and existing image list without requiring new plugin endpoints. Property and drag responses update their original element without overriding a newer selection.

Supports Minecraft **1.21.1–26.3** releases. Ordinary players using Touch or Mouse do not need this mod; Vision requires it. Each game version has a separate Fabric JAR: match the `mc` version in its filename to your client and install the corresponding Fabric API. Install only one editor JAR.

Editor **3.1.0** uses menu protocol **v10** and a separate optional Effect channel. `/arcmenu editeffect [id] [menu]` opens a dedicated EffectCraft-style workspace with a project browser, live composition with playback-scale preview, preview controls, properties and an AE-style timeline with keyframes, cubic curves, layer spans, commands, ArcMenu actions, native/CE/OGG sounds and NBS tracks. It supports real layer splitting, regional context menus, atomic keyframe editing, unified Effect undo, captured layer clipboard contents and searchable commands with keyboard bindings. **Audio Preview** predecodes loaded resource-pack OGG, displays clip waveforms and plays from arbitrary timeline offsets for beat editing, with pause/resume, overlap, looping and short scrub auditions. Native/CE events resolve their loaded sound files; NBS remains server-side. Commands and actions are suppressed in preview. The mod includes MCP authoring and real rendered screenshots; click **MCP** in the header to copy the AI connection configuration. Ordinary players can play saved effects without a mod. The server retains its older Editor and Effect protocol compatibility.

The **Minecraft 26.3** adapter integrates Vision in this same mod. Its local cursor has immediate raw mouse movement, while menu content uses a fixed projection, independent depth and UI lighting after world shaders. Arcitx uses native text input and IME without opening an anvil; leaving input keeps a draft, and only its separate submit region confirms it. The configured jump shortcut respects the server setting without making the player jump. Frontend, backend and project previews reuse the isolated menu render pass while keeping the editor's native cursor. Effect previews use native world rendering and the actual camera FOV. Other game adapters retain their editor features without advertising Vision support. Vision and isolated menu previews require the matching server implementation and runtime protocol v4.

Effect protocol **v3** negotiates compact playback state and changed preview values without resending the timeline, property schemas or resource catalogues. Existing Effect v1/v2 clients retain full snapshots, and this editor starts with v2 for older servers. Continuous timeline scrubbing sends the latest frame at up to 20 Hz and flushes the final frame on release; keyboard seeks and MCP operations remain immediate.

| Minecraft | Java | Fabric Loader |
| --- | --- | --- |
| 1.21.1–1.21.11 | 21 or newer | No additional mod version constraint |
| 26.1, 26.1.1, 26.1.2, 26.2, 26.3 | 25 or newer | No additional mod version constraint |

The game and installed Fabric API determine their own Loader requirements. `loader_version` selects the build dependency, not an installation minimum. Use the `mc1.21.1` JAR for a 1.21.1 client; the `mc26.2` JAR cannot run on 1.21.1 or Java 21.

Editor 3.0.2 retains the single-annotation Mixin Redirect format across every target, including when built with newer Loader dependencies. This fixes startup with MixinExtras 0.5.4 and earlier without requiring a Loader upgrade. Each build checks the packaged runtime JAR for this compatibility.

Build with JDK 25. The default target is 26.1.2; select another release with a Gradle property:

```sh
./gradlew build -Pminecraft_version=1.21.1
./gradlew build -Pminecraft_version=26.3
```

On Windows PowerShell, use `.\gradlew.bat build "-Pminecraft_version=26.3"`. Outputs go to `build/<minecraft-version>/libs/`. Install the JAR without the `-sources` suffix. See the [version manifest](gradle/minecraft-versions.properties) for all targets and pinned Fabric API dependencies.

Shared editor logic lives in `src/main/java`. `gradle/compatibility.gradle` translates API names at build time; `src/compat` supplies input and rendering adapters. The 1.21 builds compile with official mappings, remap to Fabric intermediary, and target Java 21 bytecode. The 26.x builds target Java 25. The 26.2/26.3 viewport compositor uses the game's rendering backend API.

CI builds and tests all 16 targets with Loader 0.18.6 and 0.19.5, including bytecode checks for required Mixin injection points and packaged Redirect annotations. These checks do not replace in-game interaction and visual testing against an ArcMenu server. On Windows, run `./scripts/build-all-versions.ps1` and `./scripts/build-all-versions.ps1 -LoaderVersion 0.19.5` for the same matrix.

The unified workspace opens from either editor or `/arcmenu projects` and `/arcmenu market`. ApricityUI is embedded inside the editor JAR; players do not install another UI mod or browser runtime. Persistent navigation connects project files, local ArcPack configuration packs, installed configuration packs and the marketplace. Entering a section refreshes its catalogue automatically. Projects provides a directory tree, independent selection checkboxes, Ctrl/Shift row selection, settings and confirmed deletion. Export creates a local archive immediately; select that archive to prepare previews or review its information and publish it. Preview preparation shows progress, supports Escape cancellation and restores drafts, playhead, audio, HUD and window dimensions. Escape closes a form or returns from a resource detail; the workspace has a separate explicit exit button. Native text fields support clipboard editing and Tab navigation; 26.3 also enables SDL text entry and native IME composition at the field caret. Background downloads do not disable workspace navigation, image decoding runs on workers, and unchanged AUI surfaces reuse their cached texture. Opening a menu editor starts in the frontend view, while ordinary editing updates preserve the chosen view.

Opening the workspace or editor automatically verifies a genuine Minecraft session through Mojang; the game's access token is never sent to the marketplace. Metadata and preview images load in the client, while archive downloads and installation run on the server. Private HTTPS endpoints may use the server-provided certificate pin with normal hostname verification. Publishing captures each saved project in an isolated, cue-free preview and stores optimized 1280×720 PNGs. MCP exposes `workspace_projects` and `workspace_market`, including native controls and screenshots, alongside the existing editor tools.

Release archives are available from [GitHub releases](https://github.com/FENTAIIII/ArcMenu-Editor/releases). **ArcMenu-Editor-3.1.0-all-versions.zip** contains all 16 version-specific runtime JARs. Community connections hide unavailable Effect tools and restrict the marketplace to operators.
