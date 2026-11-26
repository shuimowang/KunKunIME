# 构建 Rust 核心并接入本工程
# 前置：rustup target add aarch64-linux-android x86_64-linux-android；cargo install cargo-ndk
# 用法：pwsh scripts/build-rust.ps1 [-CargoProfile release|debug]
#
# 编译对象是**本仓的** rust/wind-android（UniFFI 绑定层）。输入法内核在主仓，
# 经本仓 WindInput/wind_input/crates/wind-mobile 引入，不下载另一份核心。
#   <repository>/WindInput    共享核心
#   <repository>/android      Kotlin 壳 + wind-android 绑定层
param(
    # ⚠ 不能叫 $Profile：那是 PowerShell 内置自动变量（配置文件路径），
    # 用它作参数名会让判断静默跑偏、编译不执行却仍打印成功。
    [ValidateSet("release", "debug")]
    [string]$CargoProfile = "release"
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$rustDir = Join-Path $root "rust"
$jniLibs = Join-Path $root "app\src\main\jniLibs"
$javaDir = Join-Path $root "app\src\main\java"

$coreManifest = Join-Path $root "..\WindInput\wind_input\crates\wind-mobile\Cargo.toml"
if (-not (Test-Path $coreManifest)) {
    throw "找不到共享核心：请在完整 KunKunIME 仓库中运行此脚本"
}

# 尊重已有 NDK 设置；否则从 SDK 中选最高已安装版本。
if (-not $env:ANDROID_NDK_HOME) {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
        elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
        else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    $ndk = Get-ChildItem (Join-Path $sdk "ndk") -Directory -ErrorAction SilentlyContinue |
        Sort-Object { [version]($_.Name -replace '-.*$', '') } -Descending |
        Select-Object -First 1
    if (-not $ndk) { throw "找不到 Android NDK：请设置 ANDROID_NDK_HOME 或在 Android SDK 中安装 NDK" }
    $env:ANDROID_NDK_HOME = $ndk.FullName
}
if (-not (Test-Path $env:ANDROID_NDK_HOME)) { throw "ANDROID_NDK_HOME 指向的目录不存在" }

Push-Location $rustDir
try {
    # 1. 交叉编译 .so（模拟器 x86_64 + 真机 arm64）
    #
    # ⚠ 参数一律拼进**一个数组**再整体 splat，不要写成 `... build -p x @profileArg`。
    # PowerShell 的 `if` 会把单元素数组解包成标量：`$p = if (…) { @("--release") }`
    # 得到的是 String 而非 Array，而 `@字符串` 是**按字符 splat**——实测传给 cargo 的
    # 是 `- - r e l e a s e` 八个参数，报错只有一句 `unexpected argument '-' found`，
    # 从错误信息完全看不出源头在这行。
    $ndkArgs = @(
        "--platform", "26", "-t", "x86_64", "-t", "arm64-v8a", "-o", $jniLibs,
        "build", "--locked", "-p", "wind-android"
    )
    if ($CargoProfile -eq "release") { $ndkArgs += "--release" }
    cargo ndk @ndkArgs
    if ($LASTEXITCODE -ne 0) { throw "cargo ndk 失败（exit $LASTEXITCODE）" }
    # redb 声明了 cdylib，cargo-ndk 会一并拷出，APK 不需要
    Remove-Item -Force -ErrorAction SilentlyContinue "$jniLibs\*\libredb.so", "$jniLibs\*\libredb-*.so"

    # 2. host 构建 + 生成 Kotlin 绑定（从 host dll 读元数据）
    cargo build --locked -p wind-android
    if ($LASTEXITCODE -ne 0) { throw "host build 失败" }
    # 参数一次写全：backtick 续行遇到 `--` 会被 PowerShell 解析器吃掉一段
    $targetDir = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { "target" }
    $hostLibrary = Join-Path $targetDir "debug/wind_android.dll"
    $bindgenArgs = @(
        "run", "--locked", "-p", "wind-android", "--features", "cli", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", $hostLibrary,
        "--language", "kotlin", "--out-dir", $javaDir, "--no-format"  # 与 build-rust.sh 同：不随本机 ktlint 格式化
    )
    cargo @bindgenArgs
    if ($LASTEXITCODE -ne 0) { throw "bindgen 失败" }
} finally {
    Pop-Location
}
Write-Host "OK: .so → app/src/main/jniLibs/, bindings → app/src/main/java/uniffi/"
