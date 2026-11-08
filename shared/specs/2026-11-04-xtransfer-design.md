# X互传 设计文档

版本 0.1 · 2026-11-04

## 1. 目标与非目标

**目标**

- 同一局域网（同一 WiFi/网段）内，Windows 与 Android 之间点对点互传文件、文件夹和文本。
- 两端界面观感一致：Material You（Material 3）设计语言。
- 暂存桶：先把要发的东西攒进一个全局桶，再打开会话发送。
- 会话式界面：Windows 左侧会话列表 + 右侧对话区；Android 整页列表 + 点击进入详情。

**非目标（第一版）**

- 跨网络、NAT 穿透、中继服务器。
- 账号体系、云端同步、消息已读回执。
- 与公网不可信网络互通（xlan 只适合可信内网）。
- 文件群发（原因见 §4.3）。
- 自动清理聊天历史。

## 2. 底层依赖与契约边界

底层固定使用 xlan，**不修改**。能力来自：

- Windows：`xlan.exe`（CLI + 常驻服务）。
- Android：`xlan.aar`（gomobile 绑定，只负责起停进程内服务）。
- 文档：`E:\code\go\xlan\docs\agent-guide.md`、`user-guide.md`、`android.md`。

### 2.1 必须知道的事实

| 事实 | 影响 |
|---|---|
| 本地管理 API 只监听回环（默认 `127.0.0.1:43003`，占用时顺延 43004/43005），默认 TLS 自签 | 客户端必须信任 `tls.crt`；端口要从 `config.json.api_ports` 和 `/v1/info` 探测 |
| 本地 API **没有发文件接口**；AAR 也不封装发送 | Windows 用 `xlan send` 子进程；Android 在 Kotlin 里按 `docs/android.md` §6/§7 实现 |
| `/v1/inbox` 只有「收到的」条目；自己发的**私聊文本不落库**；自己发的**文件只进 history 表**（无 peer id） | 会话历史必须由客户端自建并持久化 |
| 自己发的**公共文本**会在服务端存一条 item 并触发 `item.new` | 公共文本可由 SSE 自回显，不要重复插入 |
| `/v1/peers` 返回**所有历史发现过的设备**，无在线过滤 | 「在线」需按 `last_seen` 自行判定 |
| SSE 事件只有增量，断线不补发 | 重连后必须用 `/v1/inbox?since=` 回补 |
| SSE 会推送 `transfer.progress`（`{id, received, total}`） | **接收进度可直接用 SSE**，无需解析 CLI |
| `xlan send` 的进度只在 stderr 是真终端时输出（`internal/cli/progress.go` 的 `showProgress`） | Electron 管道 spawn 拿不到进度，需要 node-pty |
| Windows 上 `xlan pause` 是空实现，返回「当前系统不支持暂停传输」 | 不做暂停，只做取消 + 重试 |
| `xlan send --all` 不是 UDP 广播，是对 peers 表里的设备逐台 TCP/HTTPS 上传 | 文件不做群发；只有公共文本是真 UDP 广播 |
| 设备默认名取 `os.Hostname()`，Android 上常为 `localhost` | 设置里必须能改设备名 |
| 待确认 offer 发送方默认只等 60 秒 | 超时后接受会失败，UI 必须说明 |
| 单文件上限默认 4GB；重名自动加 ` (1)`；路径穿越由接收端防护 | 直接复用，不重复实现 |

### 2.2 本地 API 速查（两端共用）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/v1/info` | 本机 id/name/port/tls/fingerprint/auto_accept |
| GET | `/v1/peers` | 已知设备（含 last_seen/trusted） |
| POST | `/v1/scan?deep=1` | 主动发现（deep 约 4 秒） |
| GET | `/v1/events` | SSE |
| GET | `/v1/inbox?limit=&since=` | 收件箱 |
| GET | `/v1/item/{id}` | 单条 |
| POST | `/v1/text` | `{"body","to"}`，`to` 空为公共 |
| POST | `/v1/offer/{id}/decision` | `accept` / `reject` / `cancel` |
| POST | `/v1/trust` | `{"id","trusted"}` |
| DELETE | `/v1/item/{id}?files=1` | 删条目，可选连文件 |
| POST | `/v1/inbox/clean` | 清理旧记录 |
| POST | `/v1/cache/clean` | 清理临时分片 |
| POST | `/v1/restart` / `/v1/shutdown` | 重启 / 停止服务 |

管理端点只接受回环连接。

### 2.3 SSE 事件

```json
{"type":"item.new","ts":0,"data":{/* Item */}}
{"type":"item.update","ts":0,"data":{"id":"","status":"done|failed|active","path":"","name":"","size":0}}
{"type":"peer.online","ts":0,"data":{/* Peer */}}
{"type":"transfer.progress","ts":0,"data":{"id":"","received":0,"total":0}}
```

`peer.online` 只推新增，下线不会推送，因此在线状态要定时轮询 `/v1/peers`。

### 2.4 退出码

| 码 | 含义 | UI 映射 |
|---|---|---|
| 0 | 成功 | 已完成 |
| 1 | 一般错误 | 发送失败 |
| 2 | 用法/参数错误 | 内部错误（不应出现） |
| 3 | 找不到设备 | 设备已离线 |
| 4 | 对方拒绝 | 对方拒绝 |
| 5 | 等待确认超时 | 对方未确认 |
| 6 | 校验失败 | 文件校验失败 |

判断只用退出码和 `code` 枚举，不解析 `msg` 文案。

## 3. 总体架构

### 3.1 Windows

```
┌───────────────────────────── XTransfer.exe (Electron) ─────────────────────────────┐
│ Renderer: React 19 + MUI v9 + Emotion + @mui/icons-material                         │
│   · 会话列表 / 对话区 / 暂存桶 / 设置                                               │
│   · 只通过 preload 暴露的 IPC 调主进程                                              │
├─────────────────────────────────────────────────────────────────────────────────────┤
│ Main process                                                                        │
│   · xlan 生命周期：spawn `xlan serve -d` / `xlan stop`                              │
│   · 本地 HTTPS API 客户端（信任 xlan/tls.crt）+ SSE 长连接                          │
│   · 发送编排：spawn `xlan send ... --json`，node-pty 获取进度                       │
│   · SQLite（better-sqlite3）会话库                                                  │
│   · 托盘、系统通知、窗口控制                                                        │
└─────────────────────────────────────────────────────────────────────────────────────┘
             │ 子进程                                  │ 本地 HTTPS / SSE
             ▼                                         ▼
       xlan/xlan.exe  ───────────────────────  127.0.0.1:43003~43005
```

**目录（全部位于程序目录，不写 C 盘用户目录）**

```
XTransfer/
  XTransfer.exe
  resources/                 # electron-builder 产物
  xlan/
    xlan.exe
    config.json
    id
    xlan.db
    tls.crt / tls.key
    pins.json
  data/
    xtransfer.db
    logs/
    cache/
  recv/                      # xlan recv_dir 指向这里
```

> Electron 必须重定向 `userData`、`sessionData`、`logs`、`crashDumps` 到 `data/`。
> 不使用 electron-builder 的 `portable` 自解压目标（会解压到 `%TEMP%`，即 C 盘）；发布「目录 + zip」。

### 3.2 Android

```
┌──────────────────────────── App 进程 ────────────────────────────┐
│ Compose UI (Material 3)                                          │
│   · 会话列表 / 详情 / 暂存桶 BottomSheet / 设置                   │
│   · Repository（Room + StateFlow）                                │
├──────────────────────────────────────────────────────────────────┤
│ XlanService（dataSync 前台服务）                                  │
│   · mobile.Mobile.start(filesDir, filesDir/recv, autoAccept=false)│
│   · MulticastLock                                                 │
│   · OkHttp 本地 API 客户端（信任 filesDir/tls.crt）+ SSE          │
│   · 发送器：offer → 轮询 → 分块 PUT → finish（原生实现）           │
├──────────────────────────────────────────────────────────────────┤
│ Room：conversations / messages                                    │
└──────────────────────────────────────────────────────────────────┘
```

- AAR 与 App 同进程：Go 侧 panic 会带崩应用。
- 服务与 App 生命周期由前台服务维持；用户划掉应用后服务继续。
- 收到的文件：xlan 写 `filesDir/recv` → 发布到 MediaStore `Download/X互传` → 删除私有副本。

## 4. 数据与会话

### 4.1 本地库是唯一会话真相

xlan 的 inbox 只当传输引擎和事件源。本地库保存：

- 会话元数据
- 收到的消息/文件
- 自己发出的私聊文本和文件
- 公共文本（由 SSE 自回显）

### 4.2 表结构（Windows SQLite / Android Room 同构）

```sql
CREATE TABLE conversations (
  peer_id        TEXT PRIMARY KEY,   -- xlan peer id；广播会话固定 '__public__'
  peer_name      TEXT NOT NULL,
  platform       TEXT,
  last_message_at INTEGER NOT NULL DEFAULT 0,
  last_read_at   INTEGER NOT NULL DEFAULT 0,
  created_at     INTEGER NOT NULL
);

CREATE TABLE messages (
  id              TEXT PRIMARY KEY,  -- 入站用 xlan item.id；出站用 UUID
  conversation_id TEXT NOT NULL,
  direction       TEXT NOT NULL,     -- in | out
  kind            TEXT NOT NULL,     -- text | file | folder
  channel         TEXT NOT NULL,     -- dm | public
  body            TEXT,
  file_name       TEXT,
  file_size       INTEGER,
  local_path      TEXT,              -- Windows 路径 / Android content URI
  status          TEXT NOT NULL,     -- pending|accepted|sending|done|failed|canceled|rejected
  received        INTEGER DEFAULT 0,
  total           INTEGER DEFAULT 0,
  speed_bps       INTEGER DEFAULT 0,
  error_code      TEXT,
  xlan_item_id    TEXT,              -- 入站 item id；Android 出站为对端 offer id
  offer_id        TEXT,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL
);

CREATE INDEX idx_messages_conv ON messages(conversation_id, created_at DESC);
```

### 4.3 会话规则

- **在线设备段**：`now - last_seen <= 90s`（服务每 30 秒 announce，容忍丢两包）。
- **历史会话段**：以 `last_message_at` 倒序；有历史但设备不在线也照常显示。
- **「局域网广播」**：固定置顶，`conversation_id = '__public__'`，**只能发文本**，不显示桶的发送入口。
- 没聊过的在线设备点进去时懒创建 conversation 行（`last_message_at = 0`）。
- 未读数 = `last_read_at` 之后的 `direction = 'in'` 条数。
- 不做「已读回执」，状态里不出现「已读」。

### 4.4 事件同步

启动时：

1. `GET /v1/info` 拿本机 id/name 与服务状态。
2. `GET /v1/peers` 建立设备缓存。
3. `GET /v1/inbox?limit=500` 全量导入/对账一次。
4. 订阅 `/v1/events`。

运行中：

- `item.new` → 按 `item.id` 去重后落库。
- `item.update` → 更新状态、`path`、大小。
- `transfer.progress` → 更新对应入站文件的 `received/total/speed`。
- `peer.online` → 立即刷新设备缓存。
- 每 5 秒轮询一次 `/v1/peers` 维护在线状态。
- SSE 断线：1 秒后重连；重连成功后用 `/v1/inbox?since=最后事件时间-5s` 回补。

### 4.5 出站写入规则

- **私聊文本**：先本地插入 `status=sending`；`POST /v1/text {to: peer_id}` 返回 200 后置 `done`；失败按 HTTP 状态映射。
- **公共文本**：不本地插入；等 `item.new`（`from_id = 本机`、`channel = public`）自回显。若 2 秒内没等到，拉一次 `/v1/inbox?limit=1` 兜底。
- **文件/文件夹**：本地插入 `status=sending`；发送编排负责更新进度与终态。

## 5. 发送

### 5.1 Windows：CLI + node-pty

命令：

```
xlan.exe send <路径...> --to <peer.id> --json
```

工作目录 `<程序目录>/xlan`，保证配置、pins、数据库一致。

**伪终端**

- 用 node-pty 分配 pty，使 stderr 被判定为终端，CLI 才会画进度。
- ConPTY 会把 stdout/stderr 合并成一条流，因此：
  - 按「行」扫描；先剥离 `\r` 与 ANSI（如 `\x1b[K`）。
  - 以 `{"` 开头的行按 NDJSON 解析（每个文件×目标一行结果）。
  - 其余匹配进度/提示文本。
- pty 的 cols 设大（≥ 500），避免长 JSON 行被终端宽度折行。
- pty 创建失败 → 降级为普通 spawn + 不确定进度，不阻断发送。

**进度解析**（能解析则用，解析不到就显示不确定进度）

```
发送 <文件名>  <百分比>%  <已传> / <总量>  <速度>/s
```

**取消**：终止 pty 进程树（`taskkill /T /F /PID` 或 pty.kill）。服务进程不受影响。

**结果**：以 pty 退出码为主；stdout 的 NDJSON 用于逐文件成败、duplicate、大小。

**等待确认**：stderr 出现「等待 X 确认…」时置为 `accepted/pending` 状态；超过 CLI 的 60 秒超时后按退出码 5 处理。

### 5.2 Android：原生发送

按 `docs/android.md` §6/§7：

1. `GET /v1/peers` 取 `addr/port/id`。
2. 对端 base：`https://addr:port`（TLS 默认开）或 `http://`（TLS 关）。
3. TOFU pin：按 `host:port` 存 SharedPreferences；指纹变化拒绝并提示重置。
4. `POST {peer}/v1/offer`，字段 `from_id/from_name/to_id/kind/name/size/sha256`。
5. `duplicate:true` → 直接标记完成（对方已有同名同内容文件）。
6. `accepted:false` → 每 400ms 轮询 `GET /v1/offer/{id}`，最长 60 秒。
7. 4 MiB 分块 `PUT /v1/offer/{id}/data`，头 `Content-Range: bytes start-end/total`（闭区间），失败重试 3 次（0.4s/0.8s）。
8. 文件夹：`kind:"folder"` + `manifest`（`root`、`entries[{rel,size,sha256,dir}]`、`transfer_id`）；逐文件 `PUT /v1/offer/{id}/file/{rel}`（每段 URL 编码）；最后 `POST /v1/offer/{id}/finish`。
9. `sha256` 小写十六进制，流式计算；content URI 用 `ContentResolver.openInputStream`。

**进度与取消**：本地按已上传字节计算；取消用协程取消 + 关闭流。已发出的分块由对端按区间记录，重发时同一 offer 可续传。

### 5.3 状态机

```
发送文件/文件夹：
sending ──成功──▶ done
   │  ├──失败──▶ failed（error_code）
   │  └──用户取消──▶ canceled

接收文件：
pending ──接受──▶ accepted ──active──▶ done
   │  ├──拒绝──▶ rejected
   │  ├──超时/中断──▶ failed
   │  └──用户取消──▶ canceled

文本：
sending ──200──▶ done
   └──错误──▶ failed
```

## 6. 接收

- **文本**：xlan 自动接收，SSE `item.new` 直接入库。
- **文件/文件夹**：
  - 未信任设备：`status=pending`，会话里显示「待接收」气泡，同时发系统通知；用户点「接受」→ `POST /v1/offer/{id}/decision {"action":"accept"}`；「拒绝」→ `reject`。
  - 已信任设备：服务直接进入 `active` → `done`，无 `pending`。
  - `transfer.progress` 事件更新进度。
  - `item.update done`：
    - Windows：`path` 即 `recv/` 下的最终文件。
    - Android：把私有目录文件发布到 MediaStore `Download/X互传`，成功后删除私有副本，本地 `local_path` 存 content URI。
- **发送方只等 60 秒**：超过后接受会失败。UI 在 `pending` 超过 60 秒时提示「发送方可能已离开，让其重新发送」。
- 已信任设备管理：设置页列 `/v1/peers` 中 `trusted` 项，可取消信任（`POST /v1/trust {trusted:false}`）。
- 证书指纹：Windows 用 `xlan pin list` / `xlan pin forget <主机>`；Android 清空气泡对应的 SharedPreferences 条目。

## 7. 暂存桶

- **作用域**：全局一个桶，所有会话共享。
- **生命周期**：仅内存，应用退出即清空。
- **内容**：只记引用，不复制文件。
  - Windows：绝对路径。
  - Android：content URI + `takePersistableUriPermission`（不支持时提示）。
- **入桶**：
  - 应用内文件/文件夹选择器。
  - Windows：拖拽到窗口任意位置。
  - Android：系统「分享到 X互传」（`ACTION_SEND` / `ACTION_SEND_MULTIPLE`）。
- **入口**：
  - 列表页桶图标 + 数量角标。
  - 会话内输入框上方「桶中 N 项 + 发送」。
- **发送**：
  - 只发当前会话；「局域网广播」会话不显示文件发送入口。
  - 一批文件在 Windows 上一条 `xlan send` 命令按顺序发；Android 顺序发送。
  - 成功项自动移除；失败项保留并标红，可重试或手动移除。
  - 源文件被移动/删除时，桶内标红并提示不可用。
- **不做文件群发**：`--all` 是逐台 TCP，不是广播。

## 8. 界面规范

### 8.1 共享 Material 3 token

`shared/design-tokens.json` 维护：

- 种子色预设（默认建议 M3 基线紫 `#6750A4`，另提供蓝、绿、橙）
- 明暗两套 color roles（primary/on-primary/primary-container/…/surface/outline）
- 字号与字重、圆角（M3 shape scale）、间距、层次（tonal surface，不用重阴影）
- 动效：标准 200ms、强调 400ms，`ease-in-out` / emphasized 曲线

Windows 用 MUI theme 覆写消费；Android 用 Compose `MaterialTheme` 消费。

### 8.2 Windows

- 自绘标题栏（48dp 高）：应用名、搜索框、桶按钮、最小化/最大化/关闭。
- 左侧 320px 会话列表：
  - 顶部：搜索
  - 「在线设备」分组（绿点 + 设备名 + 平台图标）
  - 「最近会话」分组（名称、最后一条预览、时间、未读徽标）
  - 「局域网广播」固定置顶
- 右侧对话区：
  - 顶部：对端名称、在线/离线、信任开关
  - 消息流：对方左、自己右；文本气泡、文件卡片、进度条、失败重试
  - 桶提示条：`桶中 N 项 [发送]`
  - 输入区：多行输入，Enter 发送、Shift+Enter 换行
- 默认窗口 1120×720，最小 880×560。
- 托盘：`打开 / 扫描附近设备 / 退出`；关闭窗口收进托盘；退出时停止 xlan 服务。

### 8.3 Android

- 列表页：
  - 顶栏：`X互传` + 桶（角标）+ 设置
  - 搜索
  - 在线设备段 / 最近会话段；广播置顶
- 详情页：
  - 顶栏：返回 + 对端名 + 在线状态
  - 消息流同上
  - 桶提示条
  - 底部输入栏：`+`（打开桶 BottomSheet）、输入框、发送
- 设置页：见 §9。
- 首启不引导；`POST_NOTIFICATIONS` 在首次打开时申请。

### 8.4 通用

- 图标一律用图标库或自绘，禁止用文字/emoji 当图标。
- 支持深色模式；所有文字颜色必须通过主题内容色提供，不能在暗色下出现黑字压深底。
- Android 注意系统栏：显式处理 statusBars/navigationBars 内边距。
- 弹窗显示时，返回键优先关闭弹窗。
- 首页返回键连按两下退出（全局约定）。

## 9. 设置

| 分组 | 内容 |
|---|---|
| 本机信息 | 设备名、收件目录、服务状态、扫描、自检（doctor） |
| 外观 | 种子色、浅色/深色/跟随系统 |
| 信任设备与证书 | 已信任设备开关；证书指纹列表与清除 |
| 通知与清理 | 通知分类开关；清理缓存分片；清理旧收件记录 |
| Windows 专属 | 关闭行为（托盘/退出）、开机自启（应用级） |
| Android 专属 | 前台服务通知、电池优化引导 |
| 关于 | 版本、xlan 版本、开发者：明灯花月夜、网址：mdhyy.cn |

设备名修改：

- Windows：`xlan config set name <名>`（会重启服务）。
- Android：读改写 `filesDir/config.json` 的 `name` 字段，`Mobile.stop()` → `Mobile.start()`。

收件目录修改：

- Windows：`xlan config set recv_dir <路径>`，默认 `<程序目录>/recv`。
- Android：固定 `filesDir/recv`，对用户不可见；最终文件通过 MediaStore 发布。

## 10. 安全

- 仅可信内网；不做公网传输。
- 默认 TLS（自签）；首次连接 TOFU 记住指纹，变化即拒绝并提示。
- 本地管理接口仅回环；不要暴露到局域网。
- 接收端路径穿越、Windows 保留名由 xlan 处理。
- 不引入账号、不上传任何数据到第三方。

## 11. 打包与发布

**Windows**

- 便携绿色版：一个目录 + zip；`XTransfer.exe` 在根目录。
- `xlan/xlan.exe` 随包分发，版本在「关于」里展示。
- 两个原生模块：`node-pty`、`better-sqlite3`，按 Electron ABI 重编，`asar.unpacked`。
- 首次运行会弹 Windows 防火墙授权（xlan），必须允许。
- 不使用 `portable` 自解压目标。

**Android**

- `minSdk 26`，`targetSdk` 取当年最新。
- 依赖 `app/libs/xlan.aar`。
- 发布签名使用本地 `signing.properties` 中配置的密钥（密钥文件不入库）。
- 前台服务类型 `dataSync`，声明所需权限（INTERNET、ACCESS_NETWORK_STATE、ACCESS_WIFI_STATE、CHANGE_WIFI_MULTICAST_STATE、FOREGROUND_SERVICE、FOREGROUND_SERVICE_DATA_SYNC、POST_NOTIFICATIONS）。
- API 26–28 发布到 MediaStore 需要 `WRITE_EXTERNAL_STORAGE` 运行时权限。

## 12. 工程结构

```
xlan-client/
  desktop/
    package.json
    electron.vite.config.ts
    src/main/          # 生命周期、API 客户端、SSE、发送、SQLite、托盘、通知
    src/preload/       # 受控 IPC 桥
    src/renderer/      # React + MUI
    resources/xlan/    # 打包时复制 xlan.exe 与初始文件
  android/
    settings.gradle.kts
    app/
      src/main/java/... # Compose UI、Repository、Service、发送器、Room
      src/main/jniLibs/ # AAR 依赖也可用 libs/xlan.aar
  shared/
    design-tokens.json
    specs/2026-11-04-xtransfer-design.md
    icons/
```

## 13. 里程碑

| 阶段 | 内容 | 验收 |
|---|---|---|
| M1 | Windows 内核接入：起 xlan、探测端口、读 peers/inbox/SSE | 打开应用能看到本机与局域网设备、能收到文本 |
| M2 | Windows 会话 + 文本：本地库、私聊/广播、未读 | 两台 Windows 互发文本，历史重启后还在 |
| M3 | Windows 文件：暂存桶、CLI + pty 发送、接收确认、文件夹、进度/取消/重试 | 大文件有百分比；取消可中断；失败可重试 |
| M4 | Windows 外观完善：MUI M3 主题、自绘标题栏、托盘、设置、通知 | 深色模式无黑字；关闭收托盘；退出停服务 |
| M5 | Android 内核：AAR 前台服务、本地 API/SSE、Room、文本收发 | 手机与 Windows 互发文本；划掉应用仍能收 |
| M6 | Android 文件：原生发送、文件夹、SAF 入桶、MediaStore 收件 | 手机发大文件有进度；收到文件出现在 Download/X互传 |
| M7 | Android UI 对齐、设置、通知、签名 | 两端观感一致；返回键/弹窗/系统栏行为正确 |
| M8 | 联调与发布：Windows zip、APK | 完整收发矩阵通过 |

## 14. 风险与对策

| 风险 | 对策 |
|---|---|
| CLI stderr 进度格式变化 | 能解析就用，失败自动退化为不确定进度；不把 UI 绑死在文案上 |
| pty 合并 stdout/stderr 导致 JSON 与进度混流 | 行级分流：`{"` 开头按 NDJSON，其余按进度；pty 宽度调大防折行 |
| node-pty / better-sqlite3 原生模块打包失败 | CI 固定 Electron 版本；启动时自检并给出明确错误 |
| Electron 往 C 盘写缓存 | 显式重定向所有可写路径；发布目录形态而非自解压 |
| 端口 43003–43005 被外部 xlan 占用 | 比对 `/v1/info.id` 与本地 `xlan/id`；不是本目录实例就提示，不静默接管 |
| Android AAR panic 带崩应用 | 用前台服务隔离；记录日志；不做高风险并发操作 |
| 发送方 60 秒超时后接受失败 | pending 超 60 秒显示「发送方可能已离开」；允许对方重发 |
| Android 26–28 存储权限 | 运行时申请；拒绝时提示只能留在应用内/分享 |
| SSE 断线丢事件 | 重连回补 `/v1/inbox?since=`；本地库按 item id 幂等 |

## 15. 开放项

- 应用图标与托盘图标设计。
- 默认种子色最终取值（当前建议 M3 基线紫）。
- Windows 代码签名（第一版可不做）。
- 是否在后续版本支持多选会话群发（需自建逐台编排，不用 `--all`）。
