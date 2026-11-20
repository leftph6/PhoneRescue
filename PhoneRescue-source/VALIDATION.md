# 验证记录

以下为本地验证记录，不代表所有 Android 厂商、ChatGPT 版本或转换工具都兼容。日期：2026-11-20。

## 已验证

- Windows x64 自包含发布；Android APK 构建和 v2/v3 签名验证。
- Windows API：鉴权、跨源拒绝、中文输出、工作目录、重复请求复用、不同内容同 ID 拒绝、并发限制、超时、取消、失败报告、输出截断。
- 文件 API：二进制上传下载一致、重名文件隔离、路径穿越拒绝。
- 真实 C# 源文件上传 → 本机 .NET Framework 编译器离线编译 → 运行输出 `42` → 下载可执行文件。
- 中文 CSV 上传 → PowerShell 转 JSON → 下载后解析核对内容。
- USB 转发被删除后，监控脚本自动恢复。
- 早期 Android 真机 USB 命令往返、中文结果、原生配对与 JSON 导入。
- ChatGPT Android 深层输入框修复后，连续自动执行网络与工具环境两项不同诊断，退出码均为 0。
- 0.3 APK 已安装到开发测试手机，版本确认成功。
- 源码导出审计通过：50 个显式允许的源文件；不含 keystore、session、日志、UI dump、设备编号或个人绝对路径。源码 ZIP 已生成。

## 尚未完成的验证

- 0.3 手机文件选择器 → 上传 → 加工 → 保存选择器的完整真人操作流程。
- 新版 Android 256 KB 文件字节往返测试已编写，尚未在手机执行。
- ChatGPT 自主编译/转换任务至最终总结的完整实机流程；目前真实编译、转换测试走电脑 HTTP API。
- iPhone Safari 文件往返、更多 Android 厂商、DeepSeek 自动界面适配。
- GitHub Actions 工作流已提供，远端运行结果须在上传后查看。

## 复现

```powershell
dotnet publish -c Release -r win-x64 --self-contained true -o release/staging-windows
python tests/integration.py
python android/build.py
python tests/android/build.py
# 已启动本地 USB 网桥、连接自有测试手机且停止自动任务后：
python tests/usb_device.py --serial YOUR_ADB_SERIAL --install-app
```

电脑测试使用独立端口 18766 和临时工作区。真机测试会安装主 APK（指定 `--install-app` 时）及临时测试包，调用本机网桥，最后卸载测试包。它会重启目标进程；不要在正在执行任务的手机上运行。
