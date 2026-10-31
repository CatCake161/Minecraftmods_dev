# First Mod · Minecraft Cinema

基于 **Minecraft 1.21.1 + Fabric** 的影院系统模组。

当前阶段：**P0.5 已完成；P1 已开始**（屏幕/投影仪方块、共享 OpenAL device、真实 720p30 + 5.1 播放）。
测试代码和实测数据见 [spike/README.md](spike/README.md)，
扬声器与投影仪的设计/验证清单见 [spike/P05-DESIGN.md](spike/P05-DESIGN.md)。

## 环境要求

| 项目 | 版本 |
|---|---|
| Minecraft | 1.21.1 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.116.17+1.21.1 |
| Java（编译/运行游戏） | 21（`build.gradle` 已配置 toolchain，缺失时 Gradle 会自动下载） |
| Java（运行 Gradle） | 25 或更高（Loom 1.19 的要求） |
| Gradle | 9.8.2（随 wrapper 提供，无需单独安装） |

> 注意：Gradle 进程需要 JDK 25+，而模组编译目标和游戏运行使用 JDK 21。
> 本机已安装：`%USERPROFILE%\.jdks\openjdk-26.0.2.1`（Gradle）和
> `%USERPROFILE%\.jdks\temurin-21`（toolchain）。
> 在 IDEA 中把 **Gradle JVM** 设为 JDK 25+（例如 IDEA 自带的 JBR 25）即可。

## 常用命令

```powershell
# 启动带模组的客户端
.\gradlew runClient

# 启动带模组的专用服务端（首次需在 run/server 下同意 EULA）
.\gradlew runServer

# 构建发布用 jar，输出到 build/libs/
.\gradlew build

# 刷新依赖
.\gradlew --refresh-dependencies
```

在 IntelliJ IDEA 中打开项目后，Gradle 面板的 `Tasks > fabric` 下也有 `runClient` / `runServer`。

## 目录结构

```
src/main/java/com/example/firstmod/     # 主入口、方块/物品注册等（双端）
src/main/resources/                     # fabric.mod.json、纹理、模型、语言文件
src/client/java/com/example/firstmod/   # 仅客户端逻辑（渲染、按键、界面）
src/client/resources/                   # 客户端 mixin 配置
```

## 改成你自己的模组

1. `gradle.properties`：修改 `group`、`version`
2. `settings.gradle`：修改 `rootProject.name`
3. `build.gradle`：修改 `loom.mods` 中的 id
4. `src/main/resources/fabric.mod.json`：修改 `id`、`name`、`description`、`authors`、入口类
5. Java 包名 `com.example.firstmod` 全局替换，并同步修改两个 mixin json 的 `package`
6. 资源目录 `assets/firstmod` 和 mixin 配置文件名同步改名

## 版本升级

- Fabric 推荐版本：<https://fabricmc.net/develop>
- 修改 `gradle.properties` 中的 `minecraft_version`、`loader_version`、`fabric_api_version`、`loom_version` 后重新同步 Gradle。

## 常见问题

### 首次构建报 `PKIX path building failed`

`services.gradle.org` / `downloads.gradle.org` 的证书链根是较旧的 GlobalSign Root CA，部分 JDK 的 `cacerts` 未内置。本机已把该根证书导入
`%USERPROFILE%\.jdks\temurin-21\lib\security\cacerts`，并把 Gradle 发行包预置到了 wrapper 缓存。

如果以后清空了 `%USERPROFILE%\.gradle\wrapper`，可以：

1. 用 PowerShell 下载 `https://services.gradle.org/distributions/gradle-9.8.2-bin.zip`
2. 放进 `%USERPROFILE%\.gradle\wrapper\dists\gradle-9.8.2-bin\<hash>\` 目录（保留原文件名）
3. 或在运行 Gradle 时加 `-Djavax.net.ssl.trustStoreType=Windows-ROOT`

### Gradle 报 `Dependency requires at least JVM runtime version 25`

Loom 1.19 需要 JDK 25+ 运行 Gradle。把 IDEA 的 **Gradle JVM** 设为 25 或更高，
或命令行设置 `JAVA_HOME` 指向 JDK 25+。编译目标仍是 Java 21，不需要改代码。

## 许可证

CC0-1.0，可自由学习、修改和再发布。
