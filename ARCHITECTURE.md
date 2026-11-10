# 原理与架构 / How It Works

> 本文面向想理解「为什么手机上能领取原本桌面端才有的每日积分」的开发者。
> 只描述本项目公开源码的实现方式与上游可观察行为，不含任何凭据、密钥或账号数据。
> 摘要版见 [`README.md`](README.md) 的「工作原理」章节。

## 0. 一句话总述

Trae 的每日签到奖励原本由**桌面客户端**的 SOLO 谱系接口领取，网页登录换来的短期凭证
会被设备校验拒绝（表现成 `9074 当前参与用户太多`）。本项目在 Android WebView 内**复刻桌面客户端的
授权与请求契约**：打开官方授权页并注册设备号 → 截获 loopback 回调拿到长期 `refreshToken` →
`ExchangeToken` 换访问凭证 → 用与授权时**同一个设备号**调用签到接口。整个过程在手机完成，
不需要电脑、服务器或代理。

## 1. 模块图

```text
┌──────────────────────── UI 层（Activity + 原生 XML） ────────────────────────┐
│ MainActivity   账号列表 / Hero 卡 / 一键签到                                  │
│ LoginActivity  WebView 官方授权页 / 回调截获 / JS 语法降级                    │
│ CreditsActivity 积分明细   LogsActivity 签到日志（长按复制）                  │
│ SettingsActivity 分组设置   DocsActivity 四份内置文档                         │
│ NavHelper 底部 Tab    Anim 转场动画                                           │
└────────────────────────────────┬─────────────────────────────────────────────┘
                                 │
┌────────────────────────── 业务层（纯 Java，零依赖） ─────────────────────────┐
│ CheckinEngine     状态查询 → 领取 → 回查确认 → 余额刷新                      │
│ TraeApi           SOLO 接口封装 / ExchangeToken / 账本解析                    │
│ CheckinResult     纯 Java 成功判据（便于脱离 Android 验证）                   │
│ AutoScheduler     AlarmManager 排程（签到 + 03:30 保活）                     │
│ CheckinReceiver / MaintenanceReceiver / BootReceiver                          │
│ Notice            系统通知统一出口                                             │
└────────────────────────────────┬─────────────────────────────────────────────┘
                                 │
┌────────────────────────── 数据与系统层 ──────────────────────────────────────┐
│ AccountStore     SharedPreferences("trae_checkin_store") 中的一份 JSON        │
│ Prefs            SharedPreferences("settings")：开关 / 时间 / 排除账号       │
│ network_security_config.xml  仅放行 127.0.0.1 明文，其余强制 HTTPS          │
│ android:allowBackup="false"   不参与系统云备份                               │
└────────────────────────────────┬─────────────────────────────────────────────┘
                                 │ HTTPS（无自建服务器 / 无代理 / 无中间人）
                    www.trae.cn · api.trae.cn · api.trae.com.cn
```

## 2. 模块与职责

| 模块 | 职责 |
|---|---|
| `LoginActivity` | 生成设备参数、打开官方授权页、截获回调、换凭证、落库、旧内核 JS 降级 |
| `TraeApi` | 4 个 POST 接口 + `ExchangeToken` + 账本解析，统一最小请求头 |
| `CheckinEngine` | 单账号完整签到流程、错误码分流、换号重试、回查判真、写日志 |
| `CheckinResult` | 把「什么算成功」抽成纯 Java 规则，避免 UI 层自行判断 |
| `AccountStore` | 多账号 / 历史 / 设备号迁移 / 导入导出（一份 JSON） |
| `Prefs` | 设置项持久化；所有开关在 `onCreate` 读回真实值（修复开关回弹） |
| `AutoScheduler` | `AlarmManager` 排程，含随机延迟与幂等短路 |
| `CheckinReceiver` | 定时签到执行 + 通知 + 重排下一天 |
| `MaintenanceReceiver` | 每日 03:30 只换 token、不签到 |
| `BootReceiver` | 开机 / 应用更新后恢复闹钟 |
| `Notice` | 通知渠道与权限判断，异常单独处理，不影响签到主流程 |

---

## 3. 登录：官方 SOLO OAuth（全程手机端）

网页版 `/login` + cookie 换短期 JWT 的旧链路会被签到接口判为「设备校验未通过」，稳定返回
`9074`（该提示与人数无关，实际是设备校验未通过）。官方桌面客户端走 SOLO 谱系，本项目在
WebView 内对齐它：

1. **生成设备参数**：`device_id` 为 **12~20 位纯数字**（避开已有账号；一个设备号一天只能签一个账号），
   `machine_id` 为**独立的 32 位 hex**，`login_trace_id` 为 16 位 hex（仅用于回调比对）。
   重新登录时若账号已有手动设置的真机号，会**原样沿用**，不被随机号覆盖。
2. **打开授权页** `https://www.trae.cn/authorization`，带上 `client_id`、`IDE_VERSION=0.1.43`、
   `device_id` / `machine_id` 等参数，把这些设备参数一并注册给上游。
3. **截获回调**：用户完成手机号 + 验证码登录后，上游回跳
   `http://127.0.0.1:18080/authorize?...`。App 在 `shouldOverrideUrlLoading` / `onPageStarted` /
   `onReceivedError` 三处截住该 URL，**不真正起本地端口**，因此不需要电脑或本地服务。
   明文 loopback 由 `res/xml/network_security_config.xml` 单独放行，其余域名仍强制 HTTPS。
4. **换凭证**：解析回调中的 `refreshToken` / `userJwt` / `userInfo`，调
   `POST https://api.trae.com.cn/cloudide/api/v3/trae/oauth/ExchangeToken` 换访问凭证，
   再用**同一个设备号**调 `status` 校验，最后把
   `token / refreshToken / expiresAt / deviceId / machineId / uid` 整份写入应用私有
   `SharedPreferences`（`AccountStore`）。
5. **兼容性处理**：授权页是 React + Modern.js 的 SSR 输出，按钮交互依赖 bundle hydrate。
   项目**不修改页面 DOM、不写入用户数据**，唯一的 `HYDRATE_JS` 是只读探针；旧内核（Chrome/83）对
   `8049.*.js` 的私有方法与 `??=` 会抛 SyntaxError，由 `downgradeJs()` 在
   `shouldInterceptRequest` 里做语法降级（内核 ≥ 84 时不介入）。
   第三方登录（抖音 / 苹果 / 企业账号）走 `window.open`，由隐藏 WebView 承接并把首跳 URL
   转交主 WebView。

> 关键结论：`9074` 与登录无关。用无效 token 实测 `claim` 一律返回 `1001`，不返回 `9074`；
> 而 `status` / `ide_user_ent_usage` 在 9074 时仍能正常读到余额。它是上游对 `X-Device-Id` 的
> **独立设备校验**，反复重新登录并不能解决。

## 4. 凭证刷新与保活

- **惰性续期**：签到 / 查询遇到 HTTP 401 或 `code=1001` 时，`CheckinEngine.ensureFreshToken`
  用保存的 `refreshToken` 调 `ExchangeToken` 换新 token，然后**只重试一次**。
  旧版本残留的 cookie 谱系账号回落到 `GetUserToken` 兼容路径。
- **每日保活**：`MaintenanceReceiver` 每天 03:30 对所有未排除账号换新一次 token，
  **只换凭证、不发起签到**，执行完调用 `AutoScheduler.rescheduleKeepAlive()` 排下一天。
- `refreshToken` 也可能因用户改密 / 退出登录 / 长期不用而失效，此时账号标记「需重新登录」，
  从账号菜单选「重新登录」再授权一次即可。

## 5. 签到：状态 → 领取 → 回查 → 余额

`CheckinEngine.checkinAccount()` 对单个账号串行执行：

1. `POST /trae/api/v2/ug/checkin_credits/status` 查今日状态与单日奖励；
2. 若未签到且需领取，`POST /trae/api/v2/ug/checkin_credits/claim` 领取；
3. **回查 `status.checked_in == true` 才记为成功** —— 仅凭 HTTP 200 或 `code=0` 会误报
   「弹窗显示成功、积分却没到账」的假成功；
4. `POST /trae/api/v2/pay/ide_user_ent_usage` 刷新服务端账本余额；
5. 无论成功失败都写一条日志（失败含服务端原因与错误码），并更新账号卡片状态。

请求体统一为 `{"req_source":2}`（旧网页谱系的 `req_source=1` 会触发 9074）。

### 错误码处理

| code | 含义 | 处理 |
|---|---|---|
| `0` | 领取请求被接受 | 回查 `status.checked_in`，确认为 true 才记成功 |
| `1001` / HTTP 401 | 登录过期 | `refreshToken` 换 token 后重试一次；旧账号回落 cookie |
| `9004` | 设备未注册 | 提示重新登录完成设备绑定 |
| `9074` | 设备校验未通过 | 手动设备号**原样保留、不轮换**；自动号 24 小时内最多换一次重试；仍失败引导「设置设备号」 |
| `9095` | 当前设备今日已签到 | 终局失败，不重试（多账号必须分配不同设备号） |

## 6. 积分查询

- 主口径：服务端账本 `ide_user_ent_usage` 的 `usage_summary.total_amount - consumed_amount`，
  即 Trae 客户端显示的可用余额；上游旧版本无该字段时回落到
  `user_current_entitlement_list` 的权益包明细累加。
- 明细只用于展示，不参与总额计算，避免重复计数。
- 查询结果按账号聚合到「积分」页，401 时刷新 token 并重试一次。

## 7. 定时任务

- **`AlarmManager` 精确闹钟**（`SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`），
  **不用 WorkManager**（零第三方依赖的约束）；`CheckinReceiver` 执行完立即重排下一天。
- 目标时间取设定时间点中的「下一个未来时刻」，叠加 **0~N 分钟随机延迟**（避免固定时间集中请求）。
- 支持按账号**排除**：被排除账号不参与一键签到 / 首开签到 / 定时签到 / 凭证保活。
- `BootReceiver` 在开机与 `MY_PACKAGE_REPLACED` 后恢复闹钟。
- 「每天首次打开 App 自动补签」作为定时任务被系统杀掉时的第二通道兜底。
- 国产 ROM 需允许「自启动 / 后台运行 / 关闭电池优化」；设置页提供跳转入口。

## 8. 本地存储

| 数据 | 位置 | 说明 |
|---|---|---|
| 账号、token、refreshToken、cookie、uid、设备号、machineId、签到历史 | `SharedPreferences("trae_checkin_store")` 中的一份 JSON | 应用私有沙箱，其它应用无法读取 |
| 开关、签到时间、随机延迟、排除账号 | `SharedPreferences("settings")` | 每个控件在 `onCreate` 读回真实值 |
| 日志 | 同一份 JSON 的 history 段 | 长按可复制完整报错，不含 token / cookie / 完整响应体 |

- `android:allowBackup="false"`：数据**不参与系统云备份 / 设备迁移**。
- **凭证为明文存储，未加密**（如实说明）；卸载应用即删除全部本地数据；应用内「删除账号」同步清除。
- 导入 / 导出为 JSON 文本，经剪贴板完成，**不需要存储权限**；导出内容包含完整登录凭据，
  App 在复制前会给出安全提示，导入只追加、按 token / uid 去重、不覆盖。

## 9. 网络特征（诚实披露）

| 项 | 内容 |
|---|---|
| 域名 | `www.trae.cn`（授权页 / 静态资源）、`api.trae.cn`（签到与账本）、`api.trae.com.cn`（`ExchangeToken`）、`127.0.0.1:18080`（仅本机回调，不联网） |
| 认证头 | `Authorization: Cloud-IDE-JWT <token>` |
| 设备头 | `X-Device-Id: <12~20 位纯数字>`、`X-User-Region: CN` |
| 其它头 | `Content-Type: application/json`、`Accept: application/json`、`User-Agent: Trae/0.1.43` |
| 请求体 | `{"req_source":2}` |
| **刻意不发** | `x-device-brand` / `x-os-version` 等设备指纹头 —— 授权链接声明的是 `PC/1.0`，再补一套 `Windows/10.0.22631` 会自相矛盾，被上游判为伪造指纹，导致 `claim` 稳定返回 9074 |
| 自建服务器 | **无**。全部直连官方域名，无代理、无中间人、无数据回传 |
| 数据收集 | **零收集**。无广告 / 统计 / 崩溃上报 / 埋点 / 推送 SDK |

> 这些请求特征属于「对齐官方客户端契约」；是否属于平台允许的使用方式，请以 Trae 官方服务条款为准。
> 本项目只操作使用者自己的账号，
> 不提供批量 / 多租户 / 代签服务。

## 10. 已知限制与失败模式

| 场景 | 表现 | 应对 |
|---|---|---|
| 上游接口变更 | 签到 / 登录突然失效 | 无自动适配能力，需改代码；本项目不承诺持续可用 |
| 国产 ROM 后台冻结 | 定时签到不到点执行 | 允许自启动 + 关闭电池优化；首开补签兜底 |
| 断网 / 超时 | 日志记网络错误 | 15s 超时，不标记「需重新登录」，避免断网误伤账号 |
| WebView 内核过旧 | 授权页能看能输入、按钮点不动 | 内置 `downgradeJs` 语法降级；建议更新 Android System WebView |
| 官方前端换新语法 | 降级器失效 | 长按「重新发起登录」复制诊断信息反馈 |
| 设备号不被认可（9074） | 签到失败但余额可查 | 账号菜单「设置设备号」手动指定设备标识（一次性） |
| 一个设备号签多个账号 | `9095` | 保存时检测撞号，每账号分配不同号 |
| refreshToken 失效 | 提示「需重新登录」 | 重新授权一次 |

## 11. 合规风险（不要忽略）

这类「第三方客户端 + 模拟官方客户端请求」的项目常见风险：

- **违反平台服务条款**：平台可能禁止非官方客户端、自动化签到或模拟请求；
- **账号风险**：可能触发限流、验证或封禁；
- **接口稳定性**：官方改接口 / 加风控，功能随时失效；
- **商标风险**：应用名 / 图标若让用户误以为是官方产品，可能涉及侵权；
- **开源后的连带风险**：请求方式公开后可被任何人审计，平台若认为构成规避 / 滥用，可能要求下架。

本项目在 README、用户协议、隐私协议中明确：非官方、无关联、无授权；仅操作使用者自己的账号；
是否属于平台允许的使用方式，请以官方条款为准；接口变更即可能失效、不承诺可用性；如平台要求可随时停止分发。
**不承诺「安全」「稳定」「不会被封」**，使用风险自负，请遵守 Trae 服务条款与当地法律法规。
