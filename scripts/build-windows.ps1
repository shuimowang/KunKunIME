[CmdletBinding()]
param(
    [ValidateSet('dev-variant','release')][string]$Profile='dev-variant',
    [string]$OutDir,
    [string]$ReleaseDir,
    [switch]$PackageOnly,
    [switch]$Offline
)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if(-not $OutDir){$OutDir=Join-Path $project 'dist\困困输入法'}
$outputPath=$ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutDir)
$engine=Join-Path $project 'WindInput'
$version=(Get-Content -Raw -LiteralPath (Join-Path $engine 'docs\VERSION')).Trim()
$releasePath=$null
$sourceArchive=$null
if($ReleaseDir){
    if($Profile -ne 'release'){throw '-ReleaseDir 需要 -Profile release。'}
    $releasePath=$ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($ReleaseDir)
    $packagePath=$outputPath.TrimEnd([char[]]@('\','/'))
    $releaseRoot=$releasePath.TrimEnd([char[]]@('\','/'))
    if($releaseRoot -eq $packagePath -or $releaseRoot.StartsWith($packagePath+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){
        throw '发行附件目录必须位于程序输出目录之外。'
    }
    $null=New-Item -ItemType Directory -Path $releasePath -Force
    $sourceArchive=Join-Path $releasePath "KunKunIME-$version-Cargo-sources.zip"
}
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
if($releasePath){
    foreach($file in $payload){
        $actualVersion=[Diagnostics.FileVersionInfo]::GetVersionInfo($file).ProductVersion
        if($actualVersion -ne $version){throw "发行文件版本不匹配：$file 为 $actualVersion，预期 $version。"}
    }
}
$running=@(Get-Process -Name KunKunIME -ErrorAction SilentlyContinue | Where-Object {$_.Path -eq (Join-Path $outputPath 'KunKunIME.exe')})
if($running.Count){throw "目标目录有运行中的困困输入法。可以先通过 -OutDir 构建到其他目录。"}
$tempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
$stage=[IO.Directory]::CreateTempSubdirectory('KunKunIME-package-')
$bundle=Join-Path $stage.FullName '困困输入法'
try {
    $null=New-Item -Path $bundle -ItemType Directory
    foreach($file in $payload){Copy-Item -LiteralPath $file -Destination $bundle -Force}
    foreach($folder in 'data','data_custom'){
        $source=Join-Path $engine $folder
        foreach($file in Get-ChildItem -Recurse -File -LiteralPath $source){
            $rel=[IO.Path]::GetRelativePath($source,$file.FullName)
            # The published personal edition includes Pinyin, Shuangpin and supporting dictionaries.
            if($rel -match '^(comments-src|schemas[\\/]wubi86)([\\/]|$)|^schemas[\\/]wubi.*\.schema\.toml$'){continue}
            if($rel -match '^schemas[\\/]aux_code[\\/]shouyou(?:_plus)?\.txt$'){continue}
            $dest=Join-Path (Join-Path $bundle $folder) $rel
            $null=New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($dest))
            Copy-Item -LiteralPath $file.FullName -Destination $dest -Force
        }
    }
    $lic=Join-Path $bundle 'licenses'
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
    if($sourceArchive){$noticeArgs+=@('--crate-sources',$sourceArchive)}
    if($Offline){$noticeArgs+='--offline'}
    & $python @noticeArgs
    if($LASTEXITCODE -ne 0){throw 'Rust 第三方声明收集失败。'}
    Copy-Item -LiteralPath (Join-Path $project 'docs\使用说明.md') -Destination (Join-Path $bundle '使用说明.md') -Force
    Copy-Item -LiteralPath (Join-Path $project 'docs\MCP.md') -Destination (Join-Path $bundle 'MCP.md') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '卸载Windows输入法注册.md') -Destination $bundle -Force
    $packageArgs=@('-X','utf8','-B',(Join-Path $PSScriptRoot 'package-windows-files.py'),$bundle,'--output',$outputPath)
    if($releasePath){
        $windowsArchive=Join-Path $releasePath "KunKunIME-$version-Windows-x64.zip"
        $packageArgs+=@('--archive',$windowsArchive)
    }
    & $python @packageArgs
    if($LASTEXITCODE -ne 0){throw '生成程序包卸载清单失败。'}
    if($releasePath){
        $checksums=@($windowsArchive,$sourceArchive) | ForEach-Object {
            $digest=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()
            "$digest  $([IO.Path]::GetFileName($_))"
        }
        [IO.File]::WriteAllText((Join-Path $releasePath 'SHA256SUMS.txt'),($checksums -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
        Write-Host "Windows 发行附件已输出至 $releasePath"
    }
    Write-Host "困困输入法 $version 已输出至 $outputPath"
} finally {
    $owned=Get-Item -Force -LiteralPath $stage.FullName
    if($owned.Parent.FullName -ne $tempRoot -or $owned.FullName -ne $stage.FullName -or
        -not $owned.Name.StartsWith('KunKunIME-package-') -or ($owned.Attributes -band [IO.FileAttributes]::ReparsePoint)){
        throw '构建暂存路径校验失败。'
    }
    Remove-Item -Recurse -Force -LiteralPath $owned.FullName
}

