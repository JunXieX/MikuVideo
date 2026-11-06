# MikuVideo

> 作者：JunXieX　|　MikuMC系列插件交流群：1105054380　|　非开源项目，请勿二次分发

MikuVideo 让服务器里的玩家创建属于自己的“屏幕”，在游戏内直接播放网络视频。服务端只同步屏幕位置、尺寸和播放时间线，**不代理、不缓存、不解码任何视频数据**；视频由每位玩家的客户端自行连接视频源解码，因此服务器带宽与 CPU 开销几乎与视频码率无关。

适用场景：影院、家庭电视、展厅、活动直播、公告屏。

## 功能特性

- 每位玩家创建并管理自己的屏幕：位置、尺寸、朝向、音量、公开/私有。
- 播放控制：播放、暂停、继续、停止、跳转、音量；支持直链 MP4、HLS（m3u8）、FLV 等 FFmpeg 可解码的网络地址。
- 服务端权威时间线：所有观众按同一时间点播放，播放中每 5 秒自动纠偏，晚进服也能追上进度。
- 客户端本地解码：基于 FFmpeg 子进程，不占用服务端资源。
- 客户端解码预算：同时解码的屏幕数量可配，按距离与可见性调度，看不见的屏幕自动停止解码。
- Dialog 图形菜单：屏幕列表、控制页、链接/尺寸/音量输入、删除二次确认。
- Paper 与 Folia 共用同一个 jar，全部走区域化调度器。
- 权限节点、单玩家屏幕上限、全服上限、URL 私网拦截、请求头白名单、命令冷却。
- 数据持久化：JSON 原子写 + 合并写盘，坏文件自动备份，不阻塞开服。

## 环境要求

| 组件 | 要求 |
| --- | --- |
| 服务端 | Paper 或 Folia 26.3，Java 25 |
| 客户端 | Minecraft 26.3，Fabric Loader 0.20.1 或更新，Fabric API |
| 客户端 Java | Java 25 |
| 客户端解码器 | FFmpeg 6/7/9（放在 PATH、游戏目录或手动指定，也可用命令自动下载） |
| 网络 | 客户端能直接访问视频源（服务端不需要） |

## 安装

### 服务端

1. 把 `MikuVideo-1.0.0.jar` 放进 `plugins/`。
2. 重启服务器。首次启动会生成：
   - `plugins/MikuVideo/config.yml`
   - `plugins/MikuVideo/screens.json`（首次保存屏幕后出现）
   - `plugins/MikuVideo/screens.json.bak`（覆盖保存时自动备份）
3. 控制台出现 `MikuVideo 已启用：协议版本 1` 即加载成功。

### 客户端

1. 安装 Fabric Loader 0.20.1+ 与 Fabric API。
2. 把 `MikuVideo-Client-1.0.0.jar` 放进 `mods/`。
3. 安装 FFmpeg（任选一种）：
   - 已安装到系统 PATH；
   - 放到 `.minecraft/mikuvideo/ffmpeg/ffmpeg.exe`（Linux/macOS 为 `ffmpeg`）；
   - 进服后执行 `/mikuvideo-client ffmpeg <可执行文件路径>`；
   - 进服后执行 `/mikuvideo-client download` 自动下载（Windows/Linux 支持，macOS 请手动安装）。
4. 进服后默认按 `V` 与看向的屏幕交互；按键可在“按键设置 → MikuVideo”里修改。

> 客户端模组是观看与控制的必要条件。没有安装模组的玩家仍可正常进服，但看不到屏幕，服务端会提示安装。

## 快速上手

1. 看向一面墙，执行 `/mv create 客厅 4 2.25`，屏幕会贴在准星指向的方块面上。
2. 执行 `/mv play 客厅 https://example.com/video.m3u8`，3 秒缓冲后开始播放。
3. 执行 `/mv menu` 打开图形菜单，或按 `V` 对着屏幕打开控制页。
4. 用 `/mv list` 查看自己的屏幕，`/mv info 客厅` 查看详情。

## 配置说明

配置文件：`plugins/MikuVideo/config.yml`，修改后执行 `/mv admin reload`。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `limits.max-screens-per-player` | 8 | 单玩家屏幕上限，拥有 `mikuvideo.limit.unlimited` 不受限 |
| `limits.min-screen-width` / `min-screen-height` | 0.5 | 最小边长（格） |
| `limits.max-screen-width` / `max-screen-height` | 16 / 9 | 最大边长（格） |
| `limits.max-total-screens` | 512 | 全服屏幕上限，协议硬上限也是 512 |
| `sync.view-distance` | 64 | 玩家能看到多远的屏幕（格） |
| `sync.rescan-interval-ticks` | 10 | 可见集合重扫间隔（tick） |
| `sync.rebroadcast-interval-ms` | 5000 | 播放中时间线纠偏间隔（毫秒） |
| `sync.start-hold-ms` | 3000 | 点击播放后的缓冲时间（毫秒） |
| `sync.command-cooldown-ms` | 150 | 单玩家命令冷却（毫秒） |
| `sync.ping-interval-ms` | 30000 | 心跳间隔（毫秒），超时只标记不踢人 |
| `media.allow-private-hosts` | false | 是否允许 localhost/内网地址；默认拒绝 |
| `media.allowed-schemes` | http, https | 允许的协议 |
| `media.blocked-hosts` | 空 | 主机黑名单，支持 `*.example.com` |
| `media.max-url-length` | 1024 | 链接最大长度 |
| `media.max-headers` | 8 | 单次播放携带的请求头上限 |
| `media.allowed-header-names` | Referer, User-Agent, Origin, Cookie, Authorization | 允许透传的请求头 |
| `storage.save-debounce-ms` | 1500 | 合并写盘延迟（毫秒） |
| `messages.*` | 见文件 | 提示文案，支持 MiniMessage |

## 命令

根命令 `/mikuvideo`，别名 `/mv`、`/video`。

| 命令 | 说明 |
| --- | --- |
| `/mv help` | 查看命令帮助 |
| `/mv menu` | 打开屏幕列表菜单 |
| `/mv list [页码]` | 列出可管理的屏幕 |
| `/mv info <屏幕>` | 查看屏幕详情 |
| `/mv create <名称> [宽] [高]` | 看向方块面创建屏幕 |
| `/mv remove <屏幕>` | 删除屏幕 |
| `/mv rename <屏幕> <名称>` | 重命名 |
| `/mv size <屏幕> <宽> <高>` | 调整尺寸 |
| `/mv move <屏幕>` | 移动到当前看向的方块面 |
| `/mv access <屏幕> public|private` | 切换公开/私有 |
| `/mv volume <屏幕> <0-100>` | 设置音量 |
| `/mv play <屏幕> <链接> [Referer]` | 播放链接 |
| `/mv pause <屏幕>` / `resume` / `stop` | 暂停 / 继续 / 停止 |
| `/mv seek <屏幕> <秒>` | 跳转 |
| `/mv admin reload` | 重载配置 |
| `/mv admin list [页码]` | 全服屏幕列表 |
| `/mv admin remove <屏幕>` | 强制删除屏幕 |
| `/mv admin stats` | 运行统计 |
| `/mv admin purge <世界>` | 清空某个世界的屏幕 |

屏幕参数支持完整 ID、ID 前缀或名称。

### 客户端命令

| 命令 | 说明 |
| --- | --- |
| `/mikuvideo-client status` | 查看解码器、屏幕数、音量、RTT |
| `/mikuvideo-client volume <0-100>` | 设置主音量 |
| `/mikuvideo-client streams <0-16>` | 同时解码的屏幕数量上限 |
| `/mikuvideo-client ffmpeg <路径>` | 指定 FFmpeg 可执行文件 |
| `/mikuvideo-client download` | 自动下载 FFmpeg |
| `/mikuvideo-client reload` | 重载客户端配置 |

## 权限节点

| 节点 | 默认 | 说明 |
| --- | --- | --- |
| `mikuvideo.use` | 所有人 | 使用 `/mv` |
| `mikuvideo.create` | 所有人 | 创建屏幕 |
| `mikuvideo.manage.own` | 所有人 | 管理自己的屏幕 |
| `mikuvideo.manage.others` | OP | 管理其他玩家的屏幕 |
| `mikuvideo.admin` | OP | 管理子命令 |
| `mikuvideo.limit.unlimited` | OP | 不受单玩家数量限制 |

## 视频源说明

- 支持 FFmpeg 能直接打开的地址：`http(s)://...mp4`、`.m3u8`、`.flv`、`.ts` 等。
- 防盗链源可在播放时追加 Referer：`/mv play 客厅 https://cdn.example.com/a.m3u8 https://example.com/`。
- 站点页面（如视频网站播放页）不是直链，需要先用外部工具解析为直链再填入；插件不在服务端做页面解析，避免把服务器变成抓取代理。
- 默认拒绝 `localhost`、`127.0.0.0/8`、`10/8`、`172.16/12`、`192.168/16`、`169.254/16`、`::1`、`fc00::/7`、`fe80::/10`、`.local`，防止把观众客户端当成内网跳板。

## 常见问题

**玩家看不到屏幕？**
确认双方都装了客户端模组、协议版本一致、屏幕在同一世界且在 `view-distance` 内；私有屏幕只有主人和有 `mikuvideo.manage.others` 的玩家能看到。

**屏幕全黑或提示解码失败？**
先执行 `/mikuvideo-client status` 看 FFmpeg 是否就绪；再确认链接能直接打开、是否需要 Referer；HLS 直播源在首次连接时可能需要几秒。

**播放卡顿？**
降低 `/mikuvideo-client streams`，或在 `config/mikuvideo.json` 里降低 `maxWidth` / `maxHeight` / `fps` / `pixelsPerBlock`。屏幕越大、同时可见的屏幕越多，客户端解码压力越高。

**Folia 能用吗？**
可以。插件声明了 `folia-supported: true`，所有任务走全局/区域/实体/异步调度器，没有使用 `BukkitScheduler`。

**升级会丢数据吗？**
不会。替换 jar 重启即可，`config.yml` 与 `screens.json` 保持原样；格式版本变化时会按兼容方式读取。

---

作者：JunXieX　|　MikuMC系列插件交流群：1105054380　|　非开源项目，请勿二次分发
