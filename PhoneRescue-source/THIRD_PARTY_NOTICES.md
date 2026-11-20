# Third-party components

PhoneRescue's original source code is licensed under MIT, copyright 2026 lftph6. That license does not relicense external tools, SDKs, runtime files, model services or trademarks.

- Android SDK / Platform Tools: obtained from Google. SDK tools remain subject to the Android SDK terms and component licenses. Windows packages containing ADB must retain `platform-tools/NOTICE.txt` and accompanying required files. The bootstrap script downloads SDK archives; these archives are not included in the source distribution.
- .NET: Windows self-contained builds include Microsoft .NET runtime components with their upstream notices, including `LICENSE.txt` / `ThirdPartyNotices.txt` when provided by publish output. Do not strip those notices when repackaging.
- JDK: build-time tool, installed separately. Its license is determined by the selected distributor. JDK files are not shipped in the PhoneRescue source or normal Windows release.
- ChatGPT / OpenAI and other provider applications: externally installed products; no application binaries, account credentials or private SDKs are distributed here. PhoneRescue is not affiliated with or endorsed by these providers.

Build references: [Android tools](https://developer.android.com/tools), [Android SDK terms](https://developer.android.com/studio/terms), [.NET repository](https://github.com/dotnet/runtime), [Microsoft OpenJDK](https://learn.microsoft.com/en-us/java/openjdk/download).
