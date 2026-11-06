# MikuVideo 协议 v1

客户端与服务端通过 Minecraft 插件消息通道 `mikuvideo:main` 通信。单条消息第一个字节是操作码，其余字段使用大端序；字符串为 UTF-8，长度前缀 2 字节。

## 设计原则

- 服务端只广播“屏幕定义 + 播放时间线”，不传视频数据。
- 播放进度由 `positionMillis + (serverNow - startEpochMillis) * rate` 推算，不逐 tick 发包。
- 所有长度都有上限，解码失败的包直接丢弃，不影响连接。

## 服务端 -> 客户端

| op | 名称 | 字段 |
| --- | --- | --- |
| 1 | HELLO | int 协议版本、long 服务端时间、int flags、string 服务端名、string 客户端下载地址 |
| 2 | SCREEN_SYNC | int 数量、每条屏幕记录（全量替换） |
| 3 | SCREEN_UPSERT | 一条屏幕记录 |
| 4 | SCREEN_REMOVE | string 屏幕 ID |
| 5 | PLAYBACK | string 屏幕 ID、播放状态 |
| 6 | NOTICE | byte 级别、string 文本 |
| 7 | CLEAR | 无 |
| 8 | PING | long 序号 |

## 客户端 -> 服务端

| op | 名称 | 字段 |
| --- | --- | --- |
| 101 | HELLO | int 协议版本、string 模组版本、long 客户端时间 |
| 102 | INTERACT | string 屏幕 ID、byte 动作（0=打开菜单） |
| 103 | MEDIA_ERROR | string 屏幕 ID、int 错误码、string 描述 |
| 104 | PONG | long 序号、long 客户端时间 |
| 105 | DURATION | string 屏幕 ID、long 时长毫秒 |
| 106 | REQUEST_SYNC | 无 |

## 屏幕记录

`id, name, owner(2×long), world, x/y/z(double), normal(3×float), right(3×float), up(3×float), width/height(float), volume(byte), public(boolean), createdAt(long)`

## 播放状态

`url, headers(数量 + 键值), positionMillis, startEpochMillis, paused, rate, volume, audioEnabled, durationMillis`

## 时钟与纠偏

1. 客户端进服后发 HELLO，服务端回 HELLO 并带上当前时间；客户端记录 offset。
2. 播放中服务端每 5 秒重播一次时间线。
3. 客户端偏离超过 1.5 秒（落后）或 3.5 秒（超前）时 seek，两次 seek 至少间隔 7 秒。
4. 客户端第一次拿到媒体时长后上报 DURATION，服务端用于 seek 夹取。
