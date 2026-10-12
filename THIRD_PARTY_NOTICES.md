# Third-party notices

## bbs-mod icon atlas

`src/main/resources/assets/arcmenu_editor/textures/gui/editor_icons.png` is copied from
the bbs-mod project by McHorse and is used under the MIT License. The complete license
text is bundled at `src/main/resources/META-INF/LICENSE_bbs-mod.txt` and is included in
the built mod JAR.

No bbs-mod Java source is included. ArcMenu Editor independently implements its editor
state, input handling and server-authoritative protocol.

The bundled `gradle/wrapper/gradle-wrapper.jar` is Gradle's standard wrapper component.
Its Apache License 2.0 text is embedded inside the JAR as `META-INF/LICENSE`.

## Effect workspace fonts

Inter Regular and JetBrains Mono Regular are unmodified fonts from the EffectCraft reference assets.
Both are distributed under the SIL Open Font License 1.1. Their complete license texts are bundled
as `META-INF/OFL-Inter.txt` and `META-INF/OFL-JetBrainsMono.txt` in the editor JAR.
These licenses apply to the bundled fonts. They do not license ArcMenu source code.

## Embedded ApricityUI

The editor embeds the ApricityUI common HTML/CSS layout, painting and input engine
from the supplied ApricityUI project under LGPL 2.1. The engine is relocated into
`com.fentai.arcmenu.editor.aui.internal` and adapted to the editor's client surface;
its loader entrypoints, network protocol, Mixins and Rhino integration are excluded.
Players do not need to install ApricityUI or Rhino.

The complete original common source snapshot, the applied compatibility recipe and
the LGPL license are bundled at `META-INF/third-party/apricityui-source.zip`,
`META-INF/third-party/embedded-aui.gradle` and `META-INF/LICENSE-ApricityUI.txt`.
The recipe's English diagnostic replacements are included as
`META-INF/third-party/embedded-aui-en.properties`.
These licenses apply to the embedded library; they do not license ArcMenu source code.
The editor JAR contains ordinary JVM bytecode and the library is in a separate
namespace, so a compatible replacement can be rebuilt from the bundled recipe and
replace that namespace without requiring ArcMenu source code.

The bundled LWJGL GLFW Java API supplies legacy key constants used by ApricityUI.
Its native window backend is not initialized by the embedded workspace. LWJGL is
distributed under its BSD license, included inside its bundled JAR.
