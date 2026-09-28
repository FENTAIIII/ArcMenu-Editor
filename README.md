# ArcMenu Editor

**English** · [简体中文](README_zh_CN.md)

ArcMenu Editor is the administrator-side Fabric visual editor for [ArcMenu](https://github.com/FENTAIIII/ArcMenu); it places tools, an element manager, templates and properties around a live 16:9 game viewport backed by the server's real menu.

Click **Image** to show the media library in the bottom panel. Browse folders, preview resource-pack images and their dimensions, then double-click an image or click **Add** to insert it. Internal cursor and tooltip images are hidden. Click **Select** to return to templates. Previews use the loaded server resource pack and existing image list without requiring new plugin endpoints. Property and drag responses update their original element without overriding a newer selection.

Supports Minecraft **1.21.1–26.2** releases. Ordinary players do not need this mod. Each game version has a separate Fabric JAR: match the `mc` version in its filename to your client and install the corresponding Fabric API. Install only one editor JAR.

Editor **1.0.0** uses protocol **v9** and requires the matching ArcMenu plugin. Item and block properties include **Scale Z (depth)**; X/Y resizing preserves Z. ArcMenu 1.0.0 rejects older editors with an in-game upgrade message. Replace the old editor JAR and reconnect; ordinary menu users still do not need a client mod.

| Minecraft | Java | Fabric Loader |
| --- | --- | --- |
| 1.21.1–1.21.11 | 21 or newer | No additional mod version constraint |
| 26.1, 26.1.1, 26.1.2, 26.2 | 25 or newer | No additional mod version constraint |

The game and installed Fabric API determine their own Loader requirements. `loader_version` selects the build dependency, not an installation minimum. Use the `mc1.21.1` JAR for a 1.21.1 client; the `mc26.2` JAR cannot run on 1.21.1 or Java 21.

Build with JDK 25. The default target is 26.1.2; select another release with a Gradle property:

```sh
./gradlew build -Pminecraft_version=1.21.1
./gradlew build -Pminecraft_version=26.2
```

On Windows PowerShell, use `.\gradlew.bat build "-Pminecraft_version=26.2"`. Outputs go to `build/<minecraft-version>/libs/`. Install the JAR without the `-sources` suffix. See the [version manifest](gradle/minecraft-versions.properties) for all targets and pinned Fabric API dependencies.

Shared editor logic lives in `src/main/java`. `gradle/compatibility.gradle` translates API names at build time; `src/compat` supplies input and rendering adapters. The 1.21 builds compile with official mappings, remap to Fabric intermediary, and target Java 21 bytecode. The 26.x builds target Java 25. The 26.2 viewport compositor uses the game's rendering backend API.

CI builds and tests all 15 targets, including bytecode checks for required Mixin injection points. These checks do not replace in-game interaction and visual testing against an ArcMenu server.
