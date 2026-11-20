# 发布到 GitHub

## 源码仓库

根目录的 [LICENSE](../LICENSE) 为 MIT，署名 `lftph6`。README、使用指南和 AGENTS.md 可随源码发布。

建议上传由以下命令生成的干净目录或 ZIP，不要把整个开发机目录拖进 GitHub：

```powershell
python scripts/export_source.py --check
python scripts/export_source.py
```

输出：`release/PhoneRescue-source/` 与 `release/PhoneRescue-source.zip`。仅包含显式允许的源码、文档、示例、脚本和 GitHub 配置，不包含 SDK、APK、日志、手机 UI dump、设备注册表备份或签名私钥。

在干净目录初始化仓库，然后把下方 URL 替换为你实际创建的空仓库：

```powershell
cd release/PhoneRescue-source
git init -b main
git add .
git diff --cached --stat
git status --short
git commit -m "Initial open-source release"
git remote add origin https://github.com/YOUR_ACCOUNT/YOUR_REPOSITORY.git
git push -u origin main
```

也可以用 GitHub Desktop 上传此目录。确认隐藏的 `.github`、`.gitignore` 和 `.gitattributes` 被包含。工程准备不等于已创建远端仓库；远端名称和公开发布由维护者自行决定。

## CI

`.github/workflows/ci.yml` 在 Windows runner 构建电脑端、运行隔离 API 测试、配置 SDK、构建开发签名 APK，并审计源码导出。GitHub 远端是否通过应以实际运行记录为准。

CI 不连接真实手机，不发布正式 Release，也不包含稳定签名私钥。每次生成的开发签名 APK 不保证可覆盖前一次安装。

## 正式二进制 Release

1. 在干净检出安装工具链，设置私有发布签名环境变量（见 [BUILD.md](BUILD.md)）。
2. 核对版本号：AndroidManifest.xml、构建产物名、README、CHANGELOG、服务 status 版本应一致。
3. 执行 `scripts/build.ps1 -Test -Package`，在自有设备验证安装、配对、文件和官方 App 流程。
4. 核实 `VALIDATION.md`，保留未验证项，不把计划写成结果。
5. 在 GitHub Releases 手动创建对应 tag，上传 `PhoneRescue-0.3.apk`、`PhoneRescue-Windows-x64.zip`、`SHA256SUMS.txt`；源码可由 GitHub tag 自动提供，也可附源代码 ZIP。
6. 保留 MIT 许可证、第三方 notices，明确这是实验版本。不要上传测试 APK、SDK 下载缓存、开发密钥或个人日志。

四位配对码在用户启动后生成，不应预置在 Release 中。正式发布密钥不要通过 Issue、PR 或公开 Actions 日志传递。
