[CmdletBinding()]
param(
    [ValidateSet('dev-variant','release')][string]$Profile='dev-variant',
    [switch]$Offline
)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$engine=Join-Path $project 'WindInput'
$targetRoot=Join-Path $project 'target'
# MSVC 的链接器会用当前 ANSI 代码页传递临时文件名。
$buildTmp=Join-Path $project 'artifacts\build-temp'
$null=New-Item -ItemType Directory -Path $buildTmp -Force
$env:TEMP=$buildTmp
$env:TMP=$buildTmp
if(-not (Get-Command cl.exe -ErrorAction SilentlyContinue)){
    $vswhere=Join-Path ([Environment]::GetFolderPath('ProgramFilesX86')) 'Microsoft Visual Studio\Installer\vswhere.exe'
    $vs= & $vswhere -latest -prerelease -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if(-not $vs){throw '需要 Visual Studio C++ x64 / x86 桌面工具。'}
    Import-Module (Join-Path $vs 'Common7\Tools\Microsoft.VisualStudio.DevShell.dll')
    Enter-VsDevShell -VsInstallPath $vs -SkipAutomaticLocation -DevCmdArguments '-arch=x64 -host_arch=x64' | Out-Null
}
$privateTools=Join-Path $project 'artifacts\toolchain'
$localBin=Join-Path $privateTools 'rust\bin'
$localCargo=Join-Path $localBin 'cargo.exe'
if(Test-Path -LiteralPath $localCargo -PathType Leaf){
    $cargo=$localCargo
    $env:PATH=$localBin+';'+$env:PATH
    $env:CARGO_HOME=Join-Path $privateTools 'cargo'
} else {
    $command=Get-Command cargo.exe -ErrorAction SilentlyContinue
    if(-not $command){throw '需要 Rust stable，目标 x86_64-pc-windows-msvc。请安装 Rust 或在 PATH 中提供 cargo。'}
    $cargo=$command.Source
}
$env:CARGO_TARGET_DIR=$targetRoot
$parameters=@('build','--locked','--profile',$Profile,'-p','wind_service','--bin','KunKunIME')
if($Offline){$parameters+='--offline'}
Push-Location (Join-Path $engine 'wind_input')
try {
    & $cargo @parameters
    if($LASTEXITCODE -ne 0){throw 'Rust 构建失败。'}
} finally {Pop-Location}
