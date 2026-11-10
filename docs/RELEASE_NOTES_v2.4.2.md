# Trae 签到助手 v2.4.2 Release Notes

## 更新内容

**新增 / 变更**
- 界面截图换成 4 张 Android 真机截图（今日签到 / 积分明细 / 签到日志 / 设置）
- 补齐开源声明体系：`THIRD-PARTY-NOTICES.md`（原版项目 + 大量代码直接参考 + 完整 MIT 全文与各参考项目许可证列）、
  `AI-USAGE.md`（hy4 与 DeepSeek V4.1 Flash 编写声明）、`ARCHITECTURE.md`（原理与架构）、
  `SECURITY.md`、`CONTRIBUTING.md`、Issue / PR 模板
- 构建脚本不再包含明文签名口令，签名凭据改为环境变量提供；未配置时只生成一次性调试密钥
- 删除应用内用户协议中与 MIT 冲突的「禁止商用」条款；移除文档里「从 storage.json 提取设备号」的操作步骤
  （「设置设备号」功能本身保留；请确认使用方式符合 Trae 官方服务条款）
- README / 架构文档 / 内置文档语气中性化，删除自我法律定性；联系方式改为 GitHub Issues / Security Advisories
- 整理并发布完整开源仓库与 Release 资产（源码 + 文档 + 已签名 APK）

**修复**
- 无功能缺陷修复；**未改动任何 Java 业务逻辑**，登录、签到、凭证刷新、定时调度与日志保持 v2.4.1 行为

**未变更**
- 移除「积分到期提醒」是 v2.4.1 的变更，本版沿用
- **签名私钥未轮换**：证书与 v2.2.0 / 2.3.0 / 2.4.0 / 2.4.1 一致

## 安装

- 从本 Release 附件下载 `TraeCheckin-2.4.2.apk`，或访问
  [Releases](https://github.com/Liqevu324/trae-checkin-android/releases/latest)。
- 传到手机安装；要求 **Android 7.0+（minSdk 24）**。
- **可直接覆盖安装 v2.4.1 / 2.4.0 / 2.3.0 / 2.2.0**：签名证书与旧版一致，
  账号、签到记录、手动设置的设备号、设置全部保留，**无需卸载、无需重新登录**。
- 从源码自行构建、且未提供原私钥的版本签名不同，需先卸载旧版再安装（会清空本地数据）。

## 校验

```
APK SHA256:        48a74c12d81bfa86e6e1f78b429cd256ee5a968ef3e7827f654dac24066e7e5d
签名证书 SHA-256:  6f53acbfb3dc6723db5239232a1b039d003e30387727844956c6061882b824b3
versionName:       2.4.2
versionCode:       10
包名:              com.traecheckin.mobile
```

```bash
sha256sum TraeCheckin-2.4.2.apk
apksigner verify --print-certs TraeCheckin-2.4.2.apk
```

## 声明

本项目为独立开发的第三方辅助工具，与字节跳动 / Trae 官方**无任何关联、未获授权**；
仅供学习研究使用，请遵守 Trae 服务条款，使用风险自负。
**本人未授权任何收费售卖**，请只从官方 Release 渠道获取。
本项目有许多代码直接参考原版 [`wb-checkin-android`](https://github.com/Luckyboy-daidai/wb-checkin-android)，
详见 `THIRD-PARTY-NOTICES.md`；代码由 AI 编写，详见 `AI-USAGE.md`。
许可证：MIT © 2026 辉夜彩叶一休尼、我是八千代的狗
