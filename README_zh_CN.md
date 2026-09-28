# ArcMenu Editor

[English](README.md) · **简体中文**

ArcMenu Editor 是 [ArcMenu](https://github.com/FENTAIIII/ArcMenu) 的管理员端 Fabric 可视化编辑器，在服务端真实菜单的 16:9 游戏视口周围提供工具、元素管理器、模板和属性面板。

点击左侧“图片”，底部面板切换为图片素材库：按文件夹浏览资源包图片，查看缩略图及尺寸，双击图片或点击“添加”插入。鼠标指针、Tooltip 等内部图片自动隐藏；点击“选择”返回模板面板。图片预览使用已加载的服务器资源包和现有图片列表，不需要插件提供新的接口。属性调整与拖动回包只更新对应元素，不会覆盖后来选中的元素。

支持 Minecraft **1.21.1–26.2** 的正式版本，普通玩家无需安装此模组。每个游戏版本使用独立的 Fabric 模组包，请安装文件名中 `mc` 版本号与客户端一致的 JAR，并安装对应版本的 Fabric API。不要同时安装多个版本的编辑器。

编辑器 **1.0.0** 使用协议 **v9**，需要搭配新版 ArcMenu 插件。物品、方块属性面板新增 **缩放 Z（厚度）**，X/Y 拖动缩放保留 Z。ArcMenu 1.0.0 会提示旧编辑器使用者“换新的编辑器”并拒绝编辑；替换旧模组后重新连接即可，普通菜单使用者仍无需安装模组。

| Minecraft | Java | Fabric Loader |
| --- | --- | --- |
| 1.21.1–1.21.11 | 21 或更新版本 | 模组不额外限定版本 |
| 26.1、26.1.1、26.1.2、26.2 | 25 或更新版本 | 模组不额外限定版本 |

Fabric Loader 的实际要求由游戏及所安装的 Fabric API 决定。`loader_version` 仅指定构建依赖，不是模组的安装门槛。1.21.1 客户端请选择 `mc1.21.1` 包；`mc26.2` 包不能用于 1.21.1 或 Java 21。

构建环境需要 JDK 25。默认构建目标为 26.1.2，可通过 Gradle 参数选择版本（PowerShell 中保留参数引号）：

```powershell
.\gradlew.bat build "-Pminecraft_version=1.21.1"
.\gradlew.bat build "-Pminecraft_version=26.2"
```

Linux/macOS 使用 `./gradlew build -Pminecraft_version=26.2`。产物位于 `build/<游戏版本>/libs/`，安装不带 `-sources` 后缀的 JAR。完整目标及固定的 Fabric API 依赖见 [版本清单](gradle/minecraft-versions.properties)。

共享编辑器逻辑在 `src/main/java`，接口名称由 `gradle/compatibility.gradle` 在构建时转换，输入和渲染差异在 `src/compat` 中适配。1.21 系列产物使用官方映射编译并重映射为 Fabric intermediary，字节码目标为 Java 21；26.x 产物使用 Java 25。26.2 的视口合成使用游戏渲染后端接口。

CI 对全部 15 个版本执行构建、单元测试与 Mixin 目标字节码检查。这些检查不代替连接 ArcMenu 服务端后的游戏内交互和画面验证。
