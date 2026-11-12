# APK 归档与校验（2.2.0 → 2.4.4）

> 历史版本仅归档 APK 与更新说明；1.0.0 / 2.0.0 / 2.1.x 的 APK 未保留。
> 仓库源码以当前 `main` 为准，旧版本源码未单独归档。

## 版本总览

| 版本 | versionCode | 大小 | SHA256 | Release |
|---|---:|---:|---|---|
| 2.2.0 | 6 | 157 KB | `da0ea7ac3bd406c6fa4e9e48e5320bc54a503e3678ab7d3fe50173fe2e62e518` | [v2.2.0](https://github.com/Liqevu324/trae-checkin-android/releases/tag/v2.2.0) |
| 2.3.0 | 7 | 161 KB | `93ab74df9d6c686001358dcaa909601079f84f35cc35f458789f63fe6f12eb55` | [v2.3.0](https://github.com/Liqevu324/trae-checkin-android/releases/tag/v2.3.0) |
| 2.4.0 | 8 | 194 KB | `b637ce1b0c2c6dc760719cbf6c51c05c7a0454b8ea4778a3d7816ae7579ca717` | [v2.4.0](https://github.com/Liqevu324/trae-checkin-android/releases/tag/v2.4.0) |
| 2.4.1 | 9 | 194 KB | `3c5addfb55a92df2bc9bec3f50501ac780588b0e1b3c765ec8ae181090cb77b4` | [v2.4.1](https://github.com/Liqevu324/trae-checkin-android/releases/tag/v2.4.1) |
| 2.4.2 | 10 | 194 KB | `48a74c12d81bfa86e6e1f78b429cd256ee5a968ef3e7827f654dac24066e7e5d` | [v2.4.2](https://github.com/Liqevu324/trae-checkin-android/releases/tag/v2.4.2) |
| 2.4.3 | 11 | 198 KB | `849abc9e8873006ce008ce70fd42a9c713da69dc108d6dbdac824dde6622323a` | [v2.4.3](https://github.com/Liqevu324/trae-checkin-android/releases/tag/v2.4.3) |
| 2.4.4 | 12 | 198 KB | `747e1e656fd88d903e236a53e76813b101962e74d7972bfe01d84bb1914f1d61` | [v2.4.4](https://github.com/Liqevu324/trae-checkin-android/releases/tag/v2.4.4) |

## 签名

7 个 APK 使用同一把签名私钥，证书 SHA-256 均为：

```text
6f53acbfb3dc6723db5239232a1b039d003e30387727844956c6061882b824b3
CN=TraeCheckin, O=Personal, C=CN / RSA 2048 / 别名 trae
```

因此 2.2.0 → 2.4.4 可以逐个覆盖安装，账号、签到记录、手动设置的设备号与设置都会保留。
2.4.4 修复了重新登录会换掉设备号、导致 `9074` 的问题。

## 安装建议

- 只想用最新版：安装 `TraeCheckin-2.4.4.apk`。
- 想体验版本差异：从低到高依次覆盖安装即可；**不建议从高版本降级**，Android 会拒绝 `versionCode` 更低的包。
- 卸载会清空本地账号、日志与设置；卸载前请先在 App 内导出账号。
- 从源码自行构建、且未使用原私钥签名的 APK 签名不同，必须先卸载旧版再安装。

## 校验

```bash
sha256sum TraeCheckin-2.4.4.apk
apksigner verify --print-certs TraeCheckin-2.4.4.apk
```

完整 SHA256 列表见 [`SHA256SUMS.txt`](SHA256SUMS.txt)，证书信息见 [`SIGNING-CERT.txt`](SIGNING-CERT.txt)。
