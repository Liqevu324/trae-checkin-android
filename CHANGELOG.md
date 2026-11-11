# 更新日志

## v2.4.2（2026-11-11，versionCode 10）

**开源发布前的合规与文档修订；功能与签名均未改变，可直接覆盖安装。**

- 构建：`build.sh` 不再包含任何明文签名口令，改为 `SIGNING_*` 环境变量；
  未配置时只生成一次性调试密钥并明确标注不可用于发布
- 许可：删除应用内用户协议中与 MIT 冲突的「禁止商用」条款；
  `THIRD-PARTY-NOTICES.md` 与内置文档补齐各参考项目的许可证列与兼容性说明
- 合规：移除 README / 架构文档 / 内置说明书中「从 storage.json 提取设备号」的操作步骤，
  删除「绕过付费或权限控制」等自我法律定性；「设置设备号」功能本身保留不变
- 文档：README、ARCHITECTURE、PRIVACY、SECURITY、CONTRIBUTING 语气中性化；
  联系方式占位符改为 GitHub Issues / Security Advisories；
  SECURITY.md 如实披露 OAuth 回调校验、WebView 白名单、剪贴板导出等已知限制
- 界面截图替换为 4 张 Android 真机截图（1080×2340，2026-11-11 实机截取），
  README 不再标注为「由 XML 渲染生成」
- 整理并发布完整开源仓库与 Release 资产（源码 + 文档 + 已签名 APK）；
  签名沿用原私钥，未轮换，可直接覆盖安装
- 首次公开 GitHub 仓库与 Release，安装包随 Release 提供
- **未改动任何 Java 业务逻辑**：登录、签到、凭证刷新、定时调度与日志保持 v2.4.1 行为；
  签名证书不变，账号与历史记录全部保留

## v2.4.1（2026-10-11，versionCode 9）

**署名更新与功能精简。**

- 设置页「开发者」改为双署名：辉夜彩叶一休尼、我是八千代的狗；MIT 版权行同步更新
- 移除「积分到期提醒」：Trae 每日领取的积分 30 天后才过期，临期提醒意义不大，
  相关开关、首页 Hero 提示、系统通知、SharedPreferences 键与全部文案一并删除
- 其余登录、签到、凭证保活、定时、日志、文档与界面逻辑保持不变
- 补齐开源材料：`THIRD-PARTY-NOTICES.md`（明确大量代码直接参考原版项目并保留 MIT 全文）、
  `AI-USAGE.md`（声明代码由 hy4 与 DeepSeek V4.1 Flash 编写）、`ARCHITECTURE.md`（原理与架构）、
  `SECURITY.md`、`CONTRIBUTING.md`、Issue / PR 模板与 `docs/RELEASE_NOTES_v2.4.1.md`；
  应用内「开源许可与致谢」与 README 同步该声明

## v2.4.0（versionCode 8）

**界面与设置全面升级：分组式设置、内置文档、切换动画、关于与免责声明。**

- 设置页按原版重构为 5 个分组：自动任务 / 签到配置 / 后台与权限 / 数据 / 帮助与协议，
  补齐分组标题、卡片分隔线、蓝色数值链接与淡紫 Tab 选中胶囊
- 新增「每日自动凭证保活」开关：每天 03:30 由 `MaintenanceReceiver` 换新登录凭证，
  独立于自动签到开关，执行完自动排下一天
- 新增「积分到期提醒」开关：每天首次打开检查一次权益包到期时间与余额，
  7 天内到期或余额偏低时发通知并在首页提示（v2.4.1 已移除）
- 新增「管理签到时间与排除账号」：可设置签到时间并多选排除账号，
  被排除账号不参与一键签到 / 首开签到 / 定时签到 / 凭证保活，账号卡片会标注
- 新增「导入 / 导出账号」（JSON）：导出前给出凭据安全提示，导入自动预填剪贴板、
  按 token/uid 去重且只追加不覆盖
- 新增 4 份内置中文文档：使用说明书、隐私协议、用户协议与免责声明、开源许可与致谢
  （`DocsActivity` 用原生 Spannable 渲染，无需 WebView）
- 新增关于区：作者卡片、开发者、版本号、MIT 许可证与页脚免责声明
- 界面切换加入全局 Activity 转场动画（240ms 淡入 + 轻微位移）、页面内容浮入、
  Tab 选中胶囊缩放淡入；四个 Tab 页统一由 `NavHelper` 绑定
- 配色对齐原版采样：主色 `#2F6BFF`、背景 `#F5F6FB`、描述文字 `#6C757C`、
  选中胶囊 `#E8DEF7`、作者卡蓝色渐变
- 新增 `Notice` 统一通知出口，`Prefs` 扩展 5 组设置项，`AccountStore` 支持导入导出

## v2.3.0（versionCode 7）

**修复上游 `9074` 设备校验导致的签到失败 —— 纯手机端即可领到 100 积分。**

- 修正 claim 请求契约：移除与授权页声明相矛盾的 `x-device-brand=Windows` 等指纹请求头，
  严格对齐官方客户端最小请求头集合
- 修正 `machine_id` 形态为独立的 32 位 hex（此前误设为与 `device_id` 相同）
- 新增「设置设备号」入口（账号卡片菜单）：可从桌面客户端 `storage.json` 粘贴整段文本自动提取，
  也支持手动填写 12~20 位纯数字设备号
- 设备号增加来源标记（`manual` / `auto`）与 24 小时换号限频：**手动填的真机号绝不轮换**，
  自动号每日最多换一次；「重新登录」不会覆盖手动设置的设备号
- 长按日志复制的内容新增 `device_id`、`device_src`、`device_rotated`、`req_source` 诊断字段
  （不含任何登录凭据）
- `9074 / 9004` 时仍保存账号并标记失效，避免白登录一次

## v2.2.0（versionCode 6）

- 登录链路从网页版 `/login` + cookie 换短期 JWT，切换为官方 **SOLO OAuth**
  （`/authorization` + `RefreshToken → ExchangeToken` 换长期凭证），全程在手机 WebView 完成
- `device_id` 改为 12~20 位纯数字并随授权页注册；旧账号的 UUID/hex 设备号自动迁移
- 签到请求体由 `req_source=1` 改为 `req_source=2`，补齐 `X-Device-Id`、`X-User-Region` 等请求头
- 积分余额改用服务端账本 `usage_summary.total_amount - consumed_amount`
- 新增 `code=0` 后回查 `status.checked_in` 才认定成功，避免 HTTP 200 假成功
- 新增 `9074` 自动换号重试、`9095` 当日已签终局判定、`1001` 自动续期
- 新增 `res/xml/network_security_config.xml`，仅对 `127.0.0.1` 放行明文回调
- 授权页仍加载 `8049.*.js`，旧内核语法降级锚点继续有效

## v2.1.2（versionCode 5）

- 长按任意签到日志可复制完整报错（`app_version / account / time / result / code / remaining`），
  解决单行省略号截断错误码的问题
- 失败尝试同样写入历史记录并显示服务端原因

## v2.1.1（versionCode 4）

- 修复「一键签到弹窗显示成功、实际未到账」：新增 `CheckinResult` 判定，
  只有最终 `checked_in=true` 才算成功
- 失败尝试写入签到历史，记录 `success / message / code / remaining`（不含凭据）

## v2.1.0（versionCode 3）

- 登录页定位到官方 `https://www.trae.cn/login`，修复添加账号黑屏
- 移除 JSBridge，改为原生 Cookie 探针换 token
- 新增只读水合探针与诊断信息；针对 Android System WebView Chrome/83 不兼容 ES2022 语法
  （私有方法 `#foo(){}`、`??=`）的问题，对 `8049.*.js` 做语法降级
- 修复第三方登录 `window.open` 弹窗不可见
- 修复 OPPO 上通知权限弹窗与自动签到开关保存

## v2.0.0（versionCode 2，据早期构建记录补充）

- 推倒重做为纯 Java + XML 原生界面：移除 WebView / JSBridge / `assets/` / HTML，
  改为签到 / 积分 / 日志 / 设置四 Tab
- 新增 `Prefs`、`AutoScheduler`、42 个 drawable、5 档原生图标；
  `BootReceiver` 支持应用更新后恢复排程
- APK 约 137 KB

## v1.0.0（versionCode 1，据早期构建记录补充）

- 初版：双 WebView + JSBridge + HTML 界面，完成多账号登录、`AccountStore`、
  `TraeApi`、JWT 别名解析、定时签到、签到历史、通知与免责声明
