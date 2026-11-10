## 变更说明

<!-- 一句话说明改了什么、为什么。一个 PR 只做一件事。 -->

## 关联 Issue

<!-- 若有，写 Close #123 -->

## 验证方式

- [ ] `ANDROID_SDK=... bash build.sh` 7 步全部通过
- [ ] 真机走通「登录 → 签到 → 查积分」（机型 / Android 版本：______）
- [ ] 新增设置项已在 `bindValues()` 读回真实值，重进设置页不回弹
- [ ] 日志 / 诊断输出不含 token、refreshToken、cookie、完整响应体
- [ ] 涉及文案改动已同步 `res/values/strings.xml` 与 `res/raw/` 内置文档

## 截图

<!-- 界面改动请附截图，务必打码手机号、昵称、token、设备号 -->

## 引用与合规

- [ ] 未引入任何第三方依赖（零 Gradle / 零 androidx 约束）
- [ ] 若参考或引入了第三方代码 / 文案 / 图片，已更新 `THIRD-PARTY-NOTICES.md`
- [ ] 行为变更已同步 `CHANGELOG.md`
- [ ] 提交内容不含 keystore、token、cookie、真实账号数据
