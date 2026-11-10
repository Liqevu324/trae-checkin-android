# 第三方声明 / Third-Party Notices

本项目在开发过程中**直接参考、对照了原版开源项目
[`Luckyboy-daidai/wb-checkin-android`](https://github.com/Luckyboy-daidai/wb-checkin-android) 的源代码，
并且有相当数量的代码是在直接参考其实现的基础上编写而成的**。依其 MIT 许可证要求，
此处保留原项目的版权声明与许可证全文，并如实说明参考范围。

> 本文件与 [`README.md`](README.md) 顶部声明、应用内「开源许可与致谢」文档内容一致。

---

## 1. 原版项目：`wb-checkin-android`（重点声明）

| 项 | 内容 |
|---|---|
| 项目地址 | https://github.com/Luckyboy-daidai/wb-checkin-android |
| 原项目应用名 | WB签到助手 |
| 许可证 | **MIT** |
| 版权声明 | `Copyright (c) 2026 闪光男孩小志` |
| 是否复制 / 参考代码 | **是，大量代码直接参考** |
| 参考范围 | 界面结构与配色、设置页分组与开关组织、界面文案、`Prefs` 静态方法 + `SharedPreferences` 的持久化写法与开关状态回读逻辑、应用内文档的章节框架、免责声明与分发说明的措辞骨架 |
| 保留声明 | 原项目版权行 + MIT 许可证全文（见下方） |

### 1.1 关于「许多代码直接参考」的说明

本项目不是把原项目改个名字换个接口，也不是逐文件复制原工程的 Java 类——两者技术栈不同：
原项目是 **Gradle + AndroidX AppCompat / Material / RecyclerView / WorkManager** 工程，
本项目是 **零 Gradle、零 androidx、零第三方库**的纯 Android SDK 原生工程（`build.sh` 直接调用
`aapt2 / javac / d8 / zipalign / apksigner`）。因此业务层 Java 类（登录、签到、调度、存储）
由 AI 依据原项目与相关开源项目的实现思路**重新生成**。

但需要明确承认：**本项目的许多代码是直接参考原版项目写出来的**，主要包括：

- **界面与交互层**：设置页的分组结构（自动任务 / 签到配置 / 后台与权限 / 数据 / 帮助与协议）、
  卡片式开关行的组织方式、配色采样值、底部 Tab 的选中态样式，均对照原版布局实现；
- **设置持久化层**：`Prefs.java` 的类注释里即写明「写法与原版 Prefs 保持一致：全部静态方法 +
  SharedPreferences，零依赖、无状态」，开关必须在 `onCreate` 读回真实值这一约束也来自原版；
- **功能清单与文案**：功能项组织、权限说明表、免责声明与「未授权收费售卖」的分发声明框架，
  按原版 `README.md` 的章节骨架整理；
- **应用内文档**：使用说明书、隐私协议、用户协议、开源许可四份文档的分章方式，
  参照原项目 `assets/help.html` 与 `assets/privacy.html` 的结构。

也就是说：**架构骨架、界面组织、持久化写法与文档框架来自原版项目，业务逻辑由 AI 在对照参考后重写**。
本项目不主张这些部分是「完全独立实现」，并在此按 MIT 要求保留原项目版权与许可全文。

### 1.2 原项目 MIT 许可证全文

```text
MIT License

Copyright (c) 2026 闪光男孩小志

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 2. 引用链上游：`changexbc/workbuddy-switch`（间接参考）

原版 `wb-checkin-android` 的协议实现参考了该上游项目，因此构成本项目的**间接参考来源**。
本项目未直接使用其代码，仅沿引用链在此列出并保留其许可声明。

| 项 | 内容 |
|---|---|
| 项目地址 | https://github.com/changexbc/workbuddy-switch |
| 许可证 | **MIT** |
| 版权声明 | `Copyright (c) 2026 wb-switch` |
| 参考范围 | 协议语义与登录态组织（经原版项目间接了解） |
| 是否复制代码 | 否 |

```text
MIT License

Copyright (c) 2026 wb-switch

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

引用链：

```text
Trae 签到助手 / TraeCheckin（本项目，MIT）
  └─ 直接参考：Luckyboy-daidai/wb-checkin-android（MIT，Copyright (c) 2026 闪光男孩小志）
        └─ 其协议实现参考：changexbc/workbuddy-switch（MIT，Copyright (c) 2026 wb-switch）
```

---

## 3. 其他调研参考项目（仅参考公开实现思路，未复制代码）

以下项目用于确认 Trae 客户端的接口契约、设备号形态与错误码语义。本项目的 `TraeApi.java`、
`LoginActivity.java` 注释中逐处标注了对应的上游模块；**未复制其源码文件**，故不附其许可证全文，
在此列名致谢。

| 项目 | 许可证 | 参考内容 | 是否复制代码 |
|---|---|---|---|
| [`Maquer/trae-signin`](https://github.com/Maquer/trae-signin) | **MIT** | SOLO OAuth 登录链路、`client_id`、授权参数形态 | 否，仅参考公开思路 |
| [`xiaoyinge/trae-signin-gui`](https://github.com/xiaoyinge/trae-signin-gui) | **未声明许可证**（仓库无 LICENSE 文件，默认保留所有权利） | 设备号分配、`machine_id` 生成、最小请求头集合、`9074/9095` 错误码语义 | 否，仅参考可观察的接口行为与语义 |
| [`cowpy/TRAE-Automatic-sign-in`](https://github.com/cowpy/TRAE-Automatic-sign-in) | **GPL-3.0** | 签到接口早期调研 | 否，仅参考公开思路 |

> **许可证兼容性说明**：本项目采用 MIT 许可证。经核对源码，上述三个项目中
> `xiaoyinge/trae-signin-gui` 仅在 3 处 Java 注释中被引用（对齐最小请求头、
> 参考两条错误码处置规则、对齐 `machine_id` 生成形态），属于对公开契约与错误码语义的
> 引用，**没有复制或翻译任何源码文件**；`cowpy`（GPL-3.0）与 `Maquer`（MIT）同理，
> 均未引入其代码。因此不构成 MIT 与 GPL-3.0 的混用，无需变更本项目许可证。
> 若未来引入其中任一项目的代码，须按各自许可证补齐声明并重新评估整体兼容性：
> 引入 GPL-3.0 代码通常要求整体改为 GPL-3.0；未声明许可证的项目不得引入代码。

> 上述项目的版权归各自作者所有。若后续本项目引入其代码，须按各自许可证补齐声明。

---

## 4. 依赖库清单

**无第三方依赖。** 本项目为纯 Android SDK 原生实现（Java + XML）：

- 无 Gradle 依赖、无 AndroidX / AppCompat / Material / RecyclerView / WorkManager
- 无 `org.json` 以外的第三方库（`org.json` 由 Android 平台提供）
- 无广告、统计、崩溃上报、推送、埋点 SDK
- 无 NDK / JNI / 混淆 / 加固

因此**不存在需要额外声明的第三方依赖许可证**（原版项目使用的 AndroidX / Material /
WorkManager 均为 Apache-2.0，本项目未使用，故不适用）。

---

## 5. 图标、插画与截图

| 资源 | 来源 | 许可 |
|---|---|---|
| 启动图标 `mipmap-*/ic_launcher.png`、通知小图标 `ic_stat.png` | 由本项目自行绘制生成 | 随本项目 MIT |
| `res/drawable/*.xml` 全部图标 | 自制 vector / shape XML | 随本项目 MIT |
| `screenshots/*.jpg` 界面预览 | 真机截图（由使用者提供），已去除 EXIF 元数据；不含手机号、邮箱、token、cookie 或设备号，仅显示用户自设账号别名与截图时积分余额 | 随本项目 MIT |
| 字体 | 使用 Android 系统默认字体，未打包第三方字体 | 不适用 |

---

## 6. 边界说明

- 本项目**明确承认**有许多代码与结构直接参考了 `wb-checkin-android`；
  相应的版权与 MIT 许可全文已按许可证要求在本文件保留。
- 本项目代码主要由 AI 生成，详见 [`AI-USAGE.md`](AI-USAGE.md)；AI 生成过程参考了上述项目，
  不意味着这些项目的作者参与或认可本项目。
- 本项目与 Trae / 字节跳动官方**无任何关联、未获授权**，所有 Trae 相关商标归其权利人所有。
- 若任何第三方权利人认为本声明或代码引用存在问题，请通过仓库 Issue 联系，核实后将及时更正或移除。

---

本文件依 MIT 许可证要求随源码一并分发；修改或再分发本项目时，请**保留其中的版权与许可声明**。
