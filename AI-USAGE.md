# AI 辅助开发声明 / AI-USAGE

## 结论先行

**本项目代码主要由 AI 编写**，具体由以下两个模型完成：

| 模型 | 说明 |
|---|---|
| **hy4**（腾讯混元 Hunyuan，模型标识 hy4） | 主要代码生成方 |
| **DeepSeek V4.1 Flash** | 主要代码生成方 |

这里的「几乎所有代码」包括：

- `src/com/traecheckin/mobile/` 下全部 **20 个 Java 类**（登录、签到引擎、API 封装、多账号存储、
  定时调度、四个页面 Activity、文档页、动画与通知工具等）
- `res/` 下全部 **XML 布局、drawable、values、anim、raw 内置文档**
- `AndroidManifest.xml`、`build.sh` 构建脚本、`.gitignore`
- `README.md`、`CHANGELOG.md`、`PRIVACY.md`、`THIRD-PARTY-NOTICES.md`、`SECURITY.md`、
  `CONTRIBUTING.md`、`CODE_OF_CONDUCT.md` 以及本文件等**全部文档文案**
- 应用内四份中文文档（`doc_guide.txt` / `doc_privacy.txt` / `doc_terms.txt` / `doc_opensource.txt`）

## AI 用在哪些环节

| 环节 | 内容 |
|---|---|
| 需求分析与方案设计 | 从「只有手机、要领取桌面端才有的每日 100 积分」这一目标出发，确定纯手机端、零 Gradle、零第三方库的技术路线 |
| 登录链路攻关 | SOLO OAuth 授权页、loopback 回调截获、`ExchangeToken` 换凭证、旧 WebView 内核的 JS 语法降级（`downgradeJs`） |
| 签到引擎 | `req_source=2`、设备号 / `machine_id` 形态、最小请求头集合、`9074/9095/9004/1001` 错误码处理、`checked_in` 回查判真 |
| 界面与设置 | 对照原版项目的分组设置、配色、卡片、Tab 与转场动画 |
| 调试与修复 | 根据真机反馈的黑屏、按钮无响应、假成功、开关回弹、9074 等问题定位并改写代码 |
| 文档与合规 | 全部 README、声明、隐私、说明书、Release Notes、Issue / PR 模板 |
| 构建与验证 | 编写 `build.sh` 并执行 7 步构建、签名与校验 |

## 人工做了什么（如实说明）

本项目的发起与使用者**没有电脑、也未在电脑上登录过**，全部操作在手机上完成。人工工作内容是：

- 提出需求、设定约束（完全不用电脑/服务器、界面要对齐原版、私钥要中文名等）
- 在真机上**逐个版本安装、登录、签到、查积分**，并把报错日志（长按复制）反馈回来
- 对功能取舍做决定（例如：删除积分到期提醒、开发者署名写哪两个名字、开源包里放哪些文件）
- 确认最终交付物可用

**人工未对源码做逐行审阅或改写**：代码由 AI 生成、AI 自行编译构建并修复编译错误，
正确性主要通过「构建通过 + 真机实测签到成功 + 积分到账」来验证。这一点如实写明，
不套用「所有代码均经本人阅读修改」这类与事实不符的表述。

## 哪些部分不是 AI 写的

| 项 | 产出方式 |
|---|---|
| `signing.keystore` 签名私钥 | 由 JDK `keytool` 生成，非 AI 内容 |
| `TraeCheckin.apk` 成品 | 由 `aapt2 / javac / d8 / zipalign / apksigner` 构建链产出 |
| `screenshots/*.jpg` 界面预览 | 真机截图（由使用者提供，已去除 EXIF 元数据） |
| 真机实测结论与报错日志 | 由使用者在手机上产生 |
| 参考项目的公开源码与文档 | 用于核对参考范围与许可证声明 |

## 第三方参考与 AI 的关系

- 本项目**有大量代码直接参考了原版项目 `Luckyboy-daidai/wb-checkin-android`**，
  详见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)；参考范围、版权行与 MIT 全文均已在那里保留。
- AI 是在「对照参考项目源码与公开资料」的前提下生成代码的；这不等于参考项目的作者参与、
  授权或认可本项目。
- 除上述公开开源项目外，未将任何私有代码、内部资料输入 AI。

## 代码来源审查（针对第三方许可证）

本项目为 MIT 许可。为确认未引入与 MIT 不兼容的代码，已对源码做过一次核对：

- 全项目对 `xiaoyinge/trae-signin-gui` 的引用只有 3 处 Java 注释
  （对齐最小请求头集合、参考两条错误码处置规则、对齐 `machine_id` 生成形态），
  均为对公开契约与错误码语义的说明，不含其源码；
- `cowpy/TRAE-Automatic-sign-in`（GPL-3.0）与 `Maquer/trae-signin`（MIT）
  仅在致谢与调研说明中出现，未引入代码；
- 全部 Java 源码为纯 Android SDK 原生实现，无 Rust / Go / Python 代码被移植或翻译。

许可结论与各项目许可证见 `THIRD-PARTY-NOTICES.md` 第 3 节。贡献者须遵守
`CONTRIBUTING.md` 的「代码来源」约束：不得提交 GPL 系或未声明许可证项目的复制代码。

## 风险与责任

- AI 生成内容可能包含错误、冗余或与上游接口变化不同步的实现；本项目不承诺「绝对安全 / 稳定 / 不会被封」。
- 本项目与 Trae / 字节跳动官方无任何关联，接口为社区逆向的非公开实现，可能随官方版本变化而失效。
- 发起者对最终发布内容负责；若发现事实错误、引用声明遗漏或合规问题，将及时修正。

---

本声明随源码一并分发，供使用者、贡献者与仓库访客了解本项目的真实产出方式。
