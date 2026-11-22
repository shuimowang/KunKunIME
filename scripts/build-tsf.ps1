[CmdletBinding()]
param(
    [string] $OutDir = (Join-Path $PSScriptRoot '..\artifacts\windows'),
    [string] $BuildRoot = (Join-Path $PSScriptRoot '..\artifacts\.build'),
    [version] $Version = '0.1.0',
    [string] $CMake
)
$ErrorActionPreference = 'Stop'
$src = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\WindInput\wind_tsf')).Path
$outputDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutDir)
$buildDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($BuildRoot)
$locator = Join-Path ([Environment]::GetFolderPath('ProgramFilesX86')) 'Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path -LiteralPath $locator)) { throw 'Visual Studio with Desktop development with C++ is required.' }
$installed = & $locator -latest -prerelease -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -format json | ConvertFrom-Json
if (-not $installed) { throw 'Install Visual Studio C++ tools for x64 and x86.' }
$vs = @($installed)[0]
$major = ([version]$vs.installationVersion).Major
$generator = switch ($major) { 17 { 'Visual Studio 17 2022' }; 18 { 'Visual Studio 18 2026' }; default { throw "Unsupported Visual Studio version: $major" } }
if (-not $CMake) {
    $CMake = Join-Path $vs.installationPath 'Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
    if (-not (Test-Path -LiteralPath $CMake)) { $CMake = (Get-Command cmake -ErrorAction Stop).Source }
}
foreach ($arch in 'x64','Win32') {
    $target = if ($arch -eq 'x64') { 'kunkun_tsf' } else { 'kunkun_tsf_x86' }
    $build = Join-Path $buildDir "tsf-$arch"
    # Failed compiler detection leaves empty release flags in CMakeCache even after MSVC is rediscovered.
    $cache = Join-Path $build 'CMakeCache.txt'
    $configureOptions = @()
    if (Test-Path -LiteralPath $cache) {
        $cached = Get-Content -Raw -LiteralPath $cache
        if ($cached -match '(?m)^CMAKE_CXX_FLAGS_RELEASE:STRING=\r?$' -and $cached -match '(?m)^CMAKE_CXX_FLAGS:STRING=\r?$') {
            $configureOptions = @('--fresh')
        }
    }
    # CMake is invoked explicitly here; no additional regeneration project is needed.
    & $CMake @configureOptions -S $src -B $build -G $generator -A $arch '-DCMAKE_SUPPRESS_REGENERATION=ON' "-DTSF_OUTPUT_DIR=$outputDir" "-DAPP_VERSION_STR=$Version" "-DAPP_VERSION_MAJOR=$($Version.Major)" "-DAPP_VERSION_MINOR=$($Version.Minor)" "-DAPP_VERSION_PATCH=$([Math]::Max(0,$Version.Build))"
    if ($LASTEXITCODE) { throw "CMake configuration failed for $arch" }
    & $CMake --build $build --config Release --target $target -- /m:3
    if ($LASTEXITCODE) { throw "Compilation failed for $arch" }
    if (-not (Test-Path -LiteralPath (Join-Path $outputDir "$target.dll"))) { throw "Missing $target.dll" }
}

