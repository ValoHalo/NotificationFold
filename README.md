# NotificationFold · 静默通知

为 ColorOS 通知列表提供静默通知折叠组。模块使用 LSPosed，在 SystemUI 中复用原生通知组容器和动画。

当前版本：**2.1.0**。包名：`dev.oscope.notificationfold`。

## 兼容性

需要支持 **libxposed API 102** 的 LSPosed，作用域为 `com.android.systemui`（系统界面）。

## 功能

- 收起时显示无图标的“另外 N 个通知”，展开后显示原生“更多通知”顶栏。
- 摘要和子通知参与通知列表的布局与滚动，展开和收起由系统通知组处理。
- 普通通知保留在区域外，混合通知组中的 LOW/MIN 子项进入静默区。
- 保留置顶、会话、媒体等厂商专用分区；其通知继续遵循各自规则。
- 静默区内原有应用组的子项汇入同一个原生组，不显示第二层应用分组。
- 不修改通知渠道、应用发布的分组键或系统 APK；本地摘要不会通过 NotificationManager 发布。
- 锁屏继续使用系统的隐私过滤，不显示本地静默组。

## 安装与停用

1. 安装构建得到的 `notificationfold.apk`。
2. 在 LSPosed 中启用“notificationfold”，只勾选“系统界面”。
3. 重启系统界面，或重启手机。

停用时关闭该模块，并重启系统界面。模块不修改系统分区文件。

## 从源码构建

构建脚本面向 **Windows + PowerShell 7**，需要 JDK 17 或更新版本，并确保 `javac`、`jar`、`keytool` 在 `PATH` 中。

安装 Android SDK Command-line Tools，并通过 `sdkmanager` 安装依赖：

```powershell
sdkmanager.bat "platforms;android-36" "build-tools;36.0.0"
```

将 `ANDROID_HOME` 设为 Android SDK 根目录，或构建时传入 `-AndroidSdkRoot`。然后在仓库根目录运行：

```powershell
.\scripts\setup-xposed.ps1
.\build.ps1 -AndroidSdkRoot $env:ANDROID_HOME
```

依赖脚本从 Maven Central 下载 `io.github.libxposed:api:102.0.0`，校验 SHA256 后提取编译用的 `classes.jar`。API 库仅用于编译，不打入 APK。也可自行准备依赖并指定路径：

```powershell
.\build.ps1 -BuildToolsPath 'C:\Android\Sdk\build-tools\36.0.0' `
    -AndroidJar 'C:\Android\Sdk\platforms\android-36\android.jar' `
    -XposedApiJar 'C:\Dependencies\libxposed-api-102.jar'
```

构建产物为 `dist/notificationfold.apk`，脚本会验证 APK 签名。每次构建使用独立的临时编译目录，避免旧编译文件混入产物。

## 签名

个人使用时，直接运行构建脚本即可。首次构建会在 `signing/local.jks` 创建本地开发密钥，后续复用它。该密钥使用公开的开发密码 `local-build`，别名为 `local`。

对外发布时，创建自己的发行密钥。下面的命令会交互询问密码和证书信息，不会把密码写进脚本：

```powershell
New-Item -ItemType Directory -Force signing | Out-Null
keytool -genkeypair -keystore signing\notificationfold.p12 -storetype PKCS12 `
    -alias notificationfold -keyalg RSA -keysize 3072 -validity 10000
```

然后使用该密钥构建，签名工具会询问密码：

```powershell
.\build.ps1 -AndroidSdkRoot $env:ANDROID_HOME `
    -KeyStorePath .\signing\notificationfold.p12 -KeyAlias notificationfold
```

在 CI 中，可通过密钥管理功能注入环境变量，并传入 `-StorePasswordEnv NOTIFICATIONFOLD_STORE_PASSWORD`；私钥密码不同时，再传入 `-KeyPasswordEnv NOTIFICATIONFOLD_KEY_PASSWORD`。参数值是环境变量的名称，不是密码本身。指定的密钥文件不存在时，构建会报错，不会自动换用开发密钥。

本地也可以把密码单独写在 `signing/password.txt` 的第一行，不加引号，并在构建时传入 `-StorePasswordFile .\signing\password.txt`。该文件保存明文密码，仅供本地使用；整个 `signing` 目录已被 Git 忽略。

妥善备份密钥文件、别名和密码，后续更新必须使用同一签名。已有安装若使用了另一把密钥，需要使用原密钥签名，或者卸载后重装并在 LSPosed 中重新启用。签名文件已被 Git 忽略，不应提交到仓库。

## GitHub 自动构建

GitHub Actions 在 `master` 分支更新时生成发行签名 APK，也支持在 Actions 页面手动运行。拉取请求和其他分支上的手动运行使用开发签名，不读取发行密钥。

首次使用时，准备 `signing/notificationfold.p12`（密钥别名为 `notificationfold`），将密码写入 `signing/password.txt` 的第一行，再运行：

```powershell
gh auth login -h github.com -w
.\scripts\setup-github-signing.ps1
```

脚本会通过标准输入将密钥和密码保存到当前项目的 GitHub Actions Secrets，不会打印它们，也不会提交本地文件。默认仓库为 `ValoHalo/NotificationFold`，可用 `-Repository owner/repo` 指定自己的仓库。

| Actions Secret | 内容 |
| --- | --- |
| `NOTIFICATIONFOLD_KEYSTORE_BASE64` | PKCS12 密钥文件的 Base64 编码 |
| `NOTIFICATIONFOLD_STORE_PASSWORD` | 密钥库及私钥密码 |

工作流使用同一密码打开 PKCS12 密钥库和私钥。密钥只在签名步骤中临时恢复，签名后删除。缺少 Secrets 时，发行构建会报错。

构建完成后，在 Actions 对应运行的 Artifacts 中下载 `notificationfold-signed`，其中包含 `notificationfold.apk`。自动构建不会自动发布 GitHub Release。开发签名产物单独命名为 `notificationfold-development`。

## 项目结构

```text
module/
  AndroidManifest.xml
  resources/META-INF/xposed/
    java_init.list
    module.prop
    scope.list
  res/values/strings.xml
  src/dev/oscope/notificationfold/
    NotificationFold.java
    MixedGroups.java
    NativeSilentGroup.java
    SectionCollapse.java
    Hooks.java
    Reflect.java
scripts/setup-xposed.ps1
build.ps1
```

`NotificationFold` 接收模块与应用生命周期回调并恢复通知分区；`MixedGroups` 分离混合重要性的子通知；`NativeSilentGroup` 将静默项交给系统原生分组流程。`SectionCollapse` 是渲染失败时的备用实现。`Hooks` 使用 libxposed 的拦截链，`Reflect` 提供系统内部类的反射访问。

原生分组在组数量统计之前接入列表，通过系统自己的通知绑定、组状态管理、容器和动画器处理显示。模块只适配摘要内容、尺寸、可见数量和单条通知的组限制。渲染前设置私有保护标记，成功后清除；若渲染期间进程退出，下次启动使用备用实现。错误诊断保存在 SystemUI 私有目录中。

反馈问题时请提供系统版本、模块版本和复现步骤；日志或录屏中的通知正文属于个人数据，请在公开提交前自行删去。

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE)，SPDX 标识为 `GPL-3.0-only`。第三方依赖遵循各自的许可证。
