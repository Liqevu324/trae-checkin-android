# 贡献指南 / Contributing

欢迎提交 Issue 与 Pull Request。本项目是**零依赖的纯原生 Android 工程**，贡献前请先读完下面的约束。

## 1. 硬性约束（PR 会被以此审查）

- **零 Gradle、零 androidx、零第三方库**：只用 Android SDK 原生 API 与 `org.json`。
  不要引入 AppCompat / Material / WorkManager / OkHttp / Retrofit 等依赖。
- **Java 8 语法**：`build.sh` 使用 `javac --release 8`，不要用 Java 9+ API 或语法。
- **minSdk 24 / targetSdk 34**：新增 API 调用需自行做 `Build.VERSION.SDK_INT` 判断。
- **中文注释与中文文案**：界面文案放 `res/values/strings.xml`，不要硬编码在 Java 里。
- **开关必须读回真实值**：任何新增设置项都要在 `SettingsActivity.bindValues()` 从 `Prefs` 读取，
  切换即写 `Prefs`，否则会出现「重进设置页开关自己关掉」的历史缺陷。
- **日志不含凭据**：任何日志 / 诊断输出都不得包含 token、refreshToken、cookie 或完整响应体。
- **代码来源**：禁止提交复制或翻译自 **GPL 系 / 未声明许可证** 项目的代码
  （见 `THIRD-PARTY-NOTICES.md` 第 3 节）。允许参考其公开接口行为与错误码语义，
  但需在注释中注明参考来源，且必须自行实现。提交 PR 时请声明「未引入 GPL / 无许可证代码」。
- **AI 生成代码**：本项目代码主要由 AI 生成，详见 `AI-USAGE.md`；
  提交 AI 生成代码时请一并说明，并自行确认未带入第三方受版权保护的代码。

## 2. 本地构建

依赖 JDK 17+、Android SDK `build-tools;34.0.0` 与 `platforms;android-34`。

```bash
# macOS / Linux
ANDROID_SDK=/path/to/android-sdk bash build.sh

# Windows 可用 Git Bash / WSL 执行同一脚本
```

构建链：`aapt2 compile/link → javac --release 8 → d8 → zip 塞入 classes.dex → zipalign → apksigner`，
共 7 步，产物为 `TraeCheckin.apk`。**提交前必须确认 7 步全部通过。**

> 签名凭据通过 `SIGNING_KEYSTORE` / `SIGNING_STORE_PASS` / `SIGNING_KEY_PASS` /
> `SIGNING_KEY_ALIAS` 环境变量提供，脚本内不含口令；未配置时只生成一次性调试密钥。
> 自行构建的 APK 与官方 Release **签名不同，无法覆盖安装**（须先卸载，会丢本地账号数据），
> 这不代表构建失败，也不得作为发布版本分发。

## 3. 分支与提交

- 默认分支 `main`；从 `main` 切出功能分支，命名 `feat/xxx`、`fix/xxx`、`docs/xxx`。
- 提交信息用中文，一句说明「改了什么 + 为什么」，例如：`修复 9074 时手动设备号被轮换`。
- 一个 PR 聚焦一件事，不要夹带无关格式化改动。

## 4. PR 要求

- 说明变更内容与动机，附上真机验证结论（机型 / Android 版本 / 是否走完登录→签到→查积分）。
- 涉及界面的改动请附截图（**打码手机号、昵称、token、设备号**）。
- 新增引用了第三方项目 / 代码 / 文案 / 图片时，必须同步更新
  [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)，保留版权与许可证全文。
- 涉及行为变更时，同步更新 [`CHANGELOG.md`](CHANGELOG.md) 与相应应用内文档（`res/raw/`）。

## 5. Issue 规范

- Bug：填 `.github/ISSUE_TEMPLATE/bug_report.yml`，至少给出版本、机型、复现步骤、
  **长按日志复制出的完整报错**（已脱敏，可安全粘贴）。
- 功能建议：填 `feature_request.yml`，说明使用场景与是否愿意自己实现。
- 提交前先搜一下已有 Issue，避免重复。

## 6. 代码与文档风格

- 类名 / 方法名沿用现有命名；类头写一段中文注释说明职责与关键约束。
- 参考了哪个上游模块，在注释里注明（现有 `TraeApi.java` / `LoginActivity.java` 已这么做）。
- 文档用 Markdown，事实用表格；**只写确实知道的事实**，无法确认的内容请注明「待确认」并说明原因，不要编造。

## 7. 设备号与重新登录不变量（核心回归项）

任何涉及 `deviceId` / 登录 / 账号存储的改动，必须保持以下不变量：

1. **重新登录沿用旧号**：`LoginActivity.startNewAuthorization()` 在 `replaceId != null` 时
   必须复用账号已有的 `device_id` 与 `machine_id`，无论旧号是手动还是自动；只有新账号才生成新号。
2. **成功领取后锁定**：`CheckinEngine` 在 `claimed=true` 时把 `deviceIdVerified` 置为 true；
   已验证号或手动号遇到 `9074` 时不得自动调用 `rotateDeviceId`，只能提示用户设置设备号。
3. **换号必须清零**：任何修改 `deviceId` 的路径（手动设置、自动轮换、迁移、重新登录、导入）
   都必须把 `deviceIdVerified` 置为 false；`AccountStore.update()` 有强制兜底，不要绕过它直接写 JSON。
4. **新号必须唯一且合法**：新增 / 导入账号使用 12~20 位纯数字，并避开已有账号的设备号，避免 `9095`。
5. **旧数据兼容**：没有 `device_id_verified` 字段的旧账号，合法设备号按已验证处理，
   避免升级后第一次 `9074` 就换掉历史可用号。

改动后至少验证四条路径：

- 全新安装 → 首次登录 → 签到成功（积分到账）；
- 老账号 → 重新登录 → 签到成功，日志 `device_id` 与重登录前一致；
- 已中招账号 → 设置旧设备号为手动号 → 重新登录 → 签到成功；
- 多账号 / 导入账号 → 设备号不撞号，日志 `device_verified` 符合预期。

---

个人项目、随缘维护，可能不会及时回复；但每一条反馈都会被看到，感谢参与。
