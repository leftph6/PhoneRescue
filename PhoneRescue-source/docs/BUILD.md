# 从源码构建

当前构建脚本支持 **Windows x64**。Python 脚本仅使用标准库；Android 为原生 Java，不依赖 Gradle。首次安装 SDK/恢复 .NET 包需要网络，准备完后可在本地构建。

## 工具版本

- .NET **10 SDK**，命令 `dotnet --info`。
- Python **3.10+**，命令 `python --version`。
- JDK **17**，设置 `JAVA_HOME` 到 JDK 根目录；可使用 [Microsoft OpenJDK](https://learn.microsoft.com/en-us/java/openjdk/download) 或其他兼容发行版。
- Android Platform **35**、Build Tools **35.0.0**、Windows Platform Tools（ADB）。官方说明：[Android 命令行工具](https://developer.android.com/tools)。

### 已安装 Android SDK

设置 `ANDROID_HOME`（也兼容 `ANDROID_SDK_ROOT`），确保这些文件存在：

```text
%ANDROID_HOME%/platforms/android-35/android.jar
%ANDROID_HOME%/build-tools/35.0.0/aapt2.exe
%ANDROID_HOME%/platform-tools/adb.exe
%JAVA_HOME%/bin/javac.exe
```

可通过 SDK Manager 或 `sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"` 安装。许可由使用者自行按官方流程接受。

### 本地固定 SDK 下载

如果没有 SDK，先阅读 [Android SDK 许可](https://developer.android.com/studio/terms)，接受后运行：

```powershell
python scripts/bootstrap_android.py --accept-sdk-license
```

脚本从 Google 下载固定版本 ZIP，检查源码中记录的 SHA-256，再解压到被 Git 忽略的 `toolchain/`。校验失败会终止，不会继续使用下载内容。JDK 单独安装并设置 `JAVA_HOME`。如配置了 `ANDROID_HOME`，构建优先使用该 SDK；要使用本地下载的 SDK，请移除当前终端中不适用的 SDK 环境变量。

## 构建

在仓库根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build.ps1 -Test
```

等价分步命令：

```powershell
dotnet publish PhoneRescue.csproj -c Release -r win-x64 --self-contained true -o release/staging-windows
python tests/integration.py
python android/build.py
python tests/android/build.py
```

输出为 `release/staging-windows/`、`release/PhoneRescue-0.3.apk`、`tests/android/build/test.apk`。只构建电脑端可给 `scripts/build.ps1` 加 `-SkipAndroid`。

生成便携包：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build.ps1 -Test -Package
```

打包前停止从 `release/windows` 运行的旧网桥，避免覆盖被占用的 EXE。脚本不会擅自结束正在运行的用户任务。`package.py` 需要已发布的 Windows 文件、APK 和 ADB，保留 ADB 的 `NOTICE.txt`。

## APK 签名

默认在本地生成 `android/local-test.keystore`，仅用于开发。不同开发者生成不同密钥，不能互相覆盖安装；CI 的临时构建也不应被当作稳定公开发布签名。

正式发布设置以下环境变量，再运行构建脚本：

| 变量 | 含义 |
| --- | --- |
| `PHONERESCUE_KEYSTORE` | 私有签名 keystore 的路径 |
| `PHONERESCUE_KEY_ALIAS` | 密钥别名 |
| `PHONERESCUE_STORE_PASS` | keystore 密码 |
| `PHONERESCUE_KEY_PASS` | 密钥密码 |

签名工具从环境读取密码，脚本不输出它们。主 APK 和测试 APK 使用相同配置。不要把私钥或 `.env` 提交到 Git；保留发布密钥备份，后续覆盖升级需要同一身份。

## 测试

`tests/integration.py` 启动独立的 18766 端口网桥，使用临时数据目录，结束后清理。测试包含真实 C# 编译（Windows .NET Framework 编译器）、中文 CSV 转换和下载验证。可用 `PHONERESCUE_TEST_EXE` 指定被测 EXE 路径。

真机测试是可选项，需要停止手机自动任务、启动 USB 网桥并保持手机解锁：

```powershell
python tests/usb_device.py --serial YOUR_ADB_SERIAL --install-app
```

测试会安装 APK、运行协议和文件/命令集成测试并卸载临时测试包；系统安装提示由使用者确认。它不会提取官方聊天 App 的账户信息。运行 instrumentation 可能重启急救桥进程或中断无障碍服务，测试后按需要重新开启。

## 工具链不随源码提交

`toolchain/`、`bin/`、`obj/`、`release/`、构建缓存和私钥均被忽略。干净检出缺少它们是正常现象。GitHub Actions 的构建步骤见 `.github/workflows/ci.yml`；手机 UI 自动化仍需真机验证。
