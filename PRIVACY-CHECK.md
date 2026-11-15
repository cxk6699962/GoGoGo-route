# 发布前隐私与残留自检（PRIVACY-CHECK）

检查对象：`publish/GoGoGo-1.13.3`（171 个跟踪文件，24 MB）与 Release 附件（两个 APK + 源码 zip）。
检查时间：2026-11-15

## 1. 本机残留（全部为"无"）

| 检查项 | 结果 |
|---|---|
| `local.properties`（含本机 SDK 路径） | 未跟踪、目录内也不存在 |
| `.gradle/`、`build/`、`app/build/` 构建产物 | 不存在 |
| `.idea/`、`*.iml`、`.settings/` | 不存在 |
| `*.log`、`*.hprof`、崩溃转储 | 不存在 |
| `git status` | 工作区干净，无未跟踪文件 |
| 仓库体积 | 24 MB，全部为源码 + 上游 `libs/` 二进制依赖 |

## 2. 个人标识字符串（138 个文本文件全量正则扫描）

扫描模式：`cxk6699962`、`C:\Users` / `C:/Users` / `/c/Users`、`AppData`、`deepseek-harness`、`apkwork`、`default-workspace`、邮箱、手机号、IPv4、`ghp_*` / `AKIA*` 令牌。

| 结果 | 说明 |
|---|---|
| 本机用户名 / 绝对路径 / AppData / 工作区路径 | **0 命中** |
| 手机号 / 令牌 | **0 命中** |
| 邮箱 | 仅命中**上游作者**的 `zcsexp@gmail.com`（出现在 `CODE_OF_CONDUCT.md`、`SECURITY.md`、`strings.xml`），属上游公开信息，按 GPL 保留原样 |
| IPv4 | 仅 GitHub Actions 版本号误报（`auto-changelog@v4.0.4` 等） |

## 3. 二进制/打包产物抽查

| 文件 | 条目数 | 结果 |
|---|---|---|
| `Go_1.13.3_arm64-v8a_release.apk` | 955 | 无本机路径/用户名 |
| `Go_1.13.3_arm64-v8a_debug.apk` | 959 | 无本机路径/用户名 |
| `GoGoGo-1.13.3-source.zip` | 171 | 无本机路径/用户名 |

## 4. 仓库内确实存在、但属于"上游原物"的文件

| 文件 | 说明 |
|---|---|
| `keystore/GoGoGo.jks` | 上游仓库自带的签名密钥（上游公开提交的），非本机生成；本构建沿用它是为了让 1.12.3 能覆盖升级。**它不是隐私泄露，但意味着任何人拿到仓库就能签出同签名包** —— 这也是 README/免责声明里强调"认 Release 与 SHA-256"的原因 |
| `app/libs/*.so`、`BaiduLBS_Android.jar` | 上游附带的百度地图 SDK 二进制，体积主要来源（12 MB + 6.6 MB） |
| `.github/` | 上游的 CI 与模板配置，原样保留 |

## 5. 提交身份

本地提交作者为 `YG <yg@users.noreply.github.com>`（占位身份，不含真实邮箱）。
如需改成你自己的 GitHub 身份，推送前执行：

```bash
git config user.name "你的名字"
git config user.email "你的GitHub邮箱"
git commit --amend --reset-author --no-edit
git tag -f -a v1.13.3 -m "影梭 1.13.3 路径点优化版"
```

## 6. 结论

- 本仓库与两个 APK、源码包中**未发现**本机路径、用户名、账号、令牌等残留
- 无构建产物、无临时文件、无 IDE 配置
- 唯一"非源码"敏感物是上游自带的 keystore，属上游公开内容，已在文档中说明

发布安全。
