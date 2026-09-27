# SPDX-License-Identifier: GPL-3.0-only

[CmdletBinding()]
param(
    [string]$AndroidSdkRoot = $env:ANDROID_HOME,
    [string]$BuildToolsVersion = '36.0.0',
    [string]$BuildToolsPath,
    [string]$AndroidJar,
    [string]$XposedApiJar = (Join-Path $PSScriptRoot 'tools\libxposed-api-102.jar'),
    [string]$KeyStorePath,
    [string]$KeyAlias,
    [string]$StorePasswordEnv,
    [string]$StorePasswordFile,
    [string]$KeyPasswordEnv
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Run-Native([string]$command, [string[]]$arguments) {
    & $command @arguments
    if ($LASTEXITCODE -ne 0) { throw "$command failed ($LASTEXITCODE)" }
}

if (-not $AndroidSdkRoot) { $AndroidSdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $BuildToolsPath) {
    $BuildToolsPath = if ($AndroidSdkRoot) {
        Join-Path $AndroidSdkRoot "build-tools\$BuildToolsVersion"
    } else { Join-Path $PSScriptRoot 'tools\build-tools\android-16' }
}
if (-not $AndroidJar) {
    $AndroidJar = if ($AndroidSdkRoot) {
        Join-Path $AndroidSdkRoot 'platforms\android-36\android.jar'
    } else { Join-Path $PSScriptRoot 'tools\platform\android-36\android.jar' }
}

foreach ($tool in @('javac', 'jar', 'keytool')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "JDK tool missing from PATH: $tool. Install JDK 17 or newer." }
}
foreach ($path in @($AndroidJar, $XposedApiJar)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing dependency: $path. See README.md for setup." }
}
$BuildToolsPath = (Resolve-Path -LiteralPath $BuildToolsPath).Path
$AndroidJar = (Resolve-Path -LiteralPath $AndroidJar).Path
$XposedApiJar = (Resolve-Path -LiteralPath $XposedApiJar).Path
foreach ($tool in @('d8.bat', 'aapt2.exe', 'zipalign.exe', 'apksigner.bat')) {
    if (-not (Test-Path -LiteralPath (Join-Path $BuildToolsPath $tool) -PathType Leaf)) { throw "Android build tool missing: $tool" }
}

$customSigning = -not [string]::IsNullOrWhiteSpace($KeyStorePath)
if ($customSigning) {
    if (-not (Test-Path -LiteralPath $KeyStorePath -PathType Leaf)) { throw "Keystore not found: $KeyStorePath" }
    $KeyStorePath = (Resolve-Path -LiteralPath $KeyStorePath).Path
    if (-not $KeyAlias) { throw 'Specify -KeyAlias when using -KeyStorePath.' }
    if ($StorePasswordEnv -and $StorePasswordFile) {
        throw 'Use either -StorePasswordEnv or -StorePasswordFile.'
    }
    if ($StorePasswordFile) {
        $StorePasswordFile = (Resolve-Path -LiteralPath $StorePasswordFile).Path
        if ([string]::IsNullOrEmpty([System.IO.File]::ReadAllText($StorePasswordFile).TrimEnd("`r", "`n"))) {
            throw 'The signing password file is empty. Enter the keystore password on its first line.'
        }
    }
    foreach ($passwordEnv in @($StorePasswordEnv, $KeyPasswordEnv)) {
        if ($passwordEnv -and -not [Environment]::GetEnvironmentVariable($passwordEnv)) {
            throw "Password environment variable is empty: $passwordEnv"
        }
    }
} elseif ($KeyAlias -or $StorePasswordEnv -or $StorePasswordFile -or $KeyPasswordEnv) {
    throw 'Signing options require -KeyStorePath.'
}

Push-Location -LiteralPath $PSScriptRoot
try {
    # Relative resource paths also support repositories located in non-ASCII directories.
    # Fresh staging prevents obsolete compiled classes from entering the APK.
    $stageDir = 'build\module-' + [Guid]::NewGuid().ToString('N')
    $classesDir = Join-Path $stageDir 'classes'
    $dexDir = Join-Path $stageDir 'dex'
    $distDir = 'dist'
    $signingDir = 'signing'
    New-Item -ItemType Directory -Force -Path $classesDir, $dexDir, $distDir, $signingDir | Out-Null

    $sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'module\src') -Recurse -Filter '*.java' | Sort-Object FullName | ForEach-Object FullName)
    $classesJar = Join-Path $stageDir 'classes.jar'
    $resourcesZip = Join-Path $stageDir 'resources.zip'
    $unsignedApk = Join-Path $stageDir 'unsigned.apk'
    $alignedApk = Join-Path $stageDir 'aligned.apk'
    $outputApk = Join-Path $distDir 'notificationfold.apk'
    $keystore = if ($customSigning) { $KeyStorePath } else { Join-Path $signingDir 'local.jks' }

    Run-Native javac (@('-encoding','UTF-8','--release','17','-cp',"$AndroidJar;$XposedApiJar",'-d',$classesDir) + $sources)
    Run-Native jar @('cf',$classesJar,'-C',$classesDir,'.')
    Run-Native (Join-Path $BuildToolsPath 'd8.bat') @('--min-api','26','--lib',$AndroidJar,'--classpath',$XposedApiJar,'--output',$dexDir,$classesJar)
    Run-Native (Join-Path $BuildToolsPath 'aapt2.exe') @('compile','--dir','module\res','-o',$resourcesZip)
    Run-Native (Join-Path $BuildToolsPath 'aapt2.exe') @('link','-I',$AndroidJar,'--manifest','module\AndroidManifest.xml','-o',$unsignedApk,$resourcesZip)
    Run-Native jar @('uf',$unsignedApk,'-C',$dexDir,'classes.dex','-C','module\resources','META-INF')
    Run-Native (Join-Path $BuildToolsPath 'zipalign.exe') @('-f','4',$unsignedApk,$alignedApk)
    if (-not $customSigning -and -not (Test-Path -LiteralPath $keystore)) {
        Run-Native keytool @('-genkeypair','-keystore',$keystore,'-storepass','local-build','-keypass','local-build','-alias','local','-keyalg','RSA','-keysize','3072','-validity','3650','-dname','CN=NotificationFold Local')
    }
    $signArgs = @('sign','--ks',$keystore)
    if ($customSigning) {
        $signArgs += @('--ks-key-alias',$KeyAlias)
        if ($StorePasswordEnv) { $signArgs += @('--ks-pass',"env:$StorePasswordEnv") }
        if ($StorePasswordFile) { $signArgs += @('--ks-pass',"file:$StorePasswordFile") }
        if ($KeyPasswordEnv) { $signArgs += @('--key-pass',"env:$KeyPasswordEnv") }
    } else {
        $signArgs += @('--ks-key-alias','local','--ks-pass','pass:local-build','--key-pass','pass:local-build')
    }
    Run-Native (Join-Path $BuildToolsPath 'apksigner.bat') ($signArgs + @('--out',$outputApk,$alignedApk))
    Run-Native (Join-Path $BuildToolsPath 'apksigner.bat') @('verify','--verbose',$outputApk)
    Get-FileHash -LiteralPath $outputApk -Algorithm SHA256
} finally {
    Pop-Location
}
