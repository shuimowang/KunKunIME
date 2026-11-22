[CmdletBinding()]
param(
    [ValidateSet('dev-variant','release')][string]$Profile='dev-variant',
    [string]$OutDir,
    [switch]$PackageOnly,
    [switch]$Offline
)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if(-not $OutDir){$OutDir=Join-Path $project 'dist\困困输入法'}
$outputPath=$ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutDir)
$engine=Join-Path $project 'WindInput'
$version=(Get-Content -Raw -LiteralPath (Join-Path $engine 'docs\VERSION')).Trim()
$targetRoot=Join-Path $project 'target'
$native=Join-Path $project 'artifacts\windows'
if(-not $PackageOnly){
    # CMake chooses a compiler for each architecture. Keep the Rust x64 DevShell and TEMP in its own process.
    & (Join-Path $PSScriptRoot 'build-tsf.ps1') -OutDir $native -BuildRoot (Join-Path $project 'artifacts\build-windows-tsf') -Version ([version]$version)
    $shell = Join-Path $PSHOME 'pwsh.exe'
    if (-not (Test-Path -LiteralPath $shell)) { $shell = Join-Path $PSHOME 'powershell.exe' }
    $rustArgs = @('-NoProfile','-NonInteractive','-File',(Join-Path $PSScriptRoot 'build-core.ps1'),'-Profile',$Profile)
    if ($Offline) { $rustArgs += '-Offline' }
    & $shell @rustArgs
    if ($LASTEXITCODE -ne 0) { throw 'Rust 构建失败。' }
}
$exe=Join-Path $targetRoot "$Profile\KunKunIME.exe"
$payload=@($exe,(Join-Path $native 'kunkun_tsf.dll'),(Join-Path $native 'kunkun_tsf_x86.dll'))
foreach($file in $payload){if(-not (Test-Path -LiteralPath $file -PathType Leaf)){throw "缺少构建产物 $file"}}
if([Diagnostics.FileVersionInfo]::GetVersionInfo($exe).ProductName -notlike '*困困*'){throw 'EXE 产品标识不正确。'}
$running=@(Get-Process -Name KunKunIME -ErrorAction SilentlyContinue | Where-Object {$_.Path -eq (Join-Path $outputPath 'KunKunIME.exe')})
if($running.Count){throw "目标目录有运行中的困困输入法。可以先通过 -OutDir 构建到其他目录。"}
$null=New-Item -Path $outputPath -ItemType Directory -Force
foreach($file in $payload){Copy-Item -LiteralPath $file -Destination $outputPath -Force}
foreach($folder in 'data','data_custom'){
    $source=Join-Path $engine $folder
    foreach($file in Get-ChildItem -Recurse -File -LiteralPath $source){
        $rel=[IO.Path]::GetRelativePath($source,$file.FullName)
        # The published personal edition includes Pinyin, Shuangpin and supporting dictionaries.
        if($rel -match '^(comments-src|schemas[\\/]wubi86)([\\/]|$)|^schemas[\\/]wubi.*\.schema\.toml$'){continue}
        if($rel -match '^schemas[\\/]aux_code[\\/]shouyou(?:_plus)?\.txt$'){continue}
        $dest=Join-Path (Join-Path $outputPath $folder) $rel
        $null=New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($dest))
        Copy-Item -LiteralPath $file.FullName -Destination $dest -Force
    }
}
$lic=Join-Path $outputPath 'licenses'
$null=New-Item -ItemType Directory -Path $lic -Force
$licenses=@{
    'GPL-3.0.txt'=(Join-Path $engine 'LICENSE');
    'LGPL-3.0.txt'=(Join-Path $engine 'data\emoji\LICENSE');
    'wind-setting-LICENSE.txt'=(Join-Path $project 'wind-setting\LICENSE');
    'Apache-2.0.txt'=(Join-Path $project 'wind-ui-rust\LICENSE-APACHE');
    'windui-MIT.txt'=(Join-Path $project 'wind-ui-rust\LICENSE-MIT');
    'NOTICE-upstream.md'=(Join-Path $engine 'NOTICE.md');
    'ORIGINS.md'=(Join-Path $project 'ORIGINS.md')
}
foreach($name in $licenses.Keys){Copy-Item -LiteralPath $licenses[$name] -Destination (Join-Path $lic $name) -Force}
Copy-Item -LiteralPath (Join-Path $project 'licenses\THIRD-PARTY.md') -Destination $lic -Force
Copy-Item -LiteralPath (Join-Path $project 'licenses\data') -Destination $lic -Recurse -Force
$python=(Get-Command python -ErrorAction Stop).Source
$noticeArgs=@('-X','utf8','-B',(Join-Path $PSScriptRoot 'collect-cargo-notices.py'),'--output',(Join-Path $lic 'rust'))
if($Offline){$noticeArgs+='--offline'}
& $python @noticeArgs
if($LASTEXITCODE -ne 0){throw 'Rust 第三方声明收集失败。'}
Copy-Item -LiteralPath (Join-Path $project 'docs\使用说明.md') -Destination (Join-Path $outputPath '使用说明.md') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '卸载Windows输入法注册.md') -Destination $outputPath -Force
Write-Host "困困输入法 $version 已输出至 $outputPath"

