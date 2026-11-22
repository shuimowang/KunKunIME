<# Tests physical full-Pinyin input via the installed system DLL in a temporary RichEdit window. The host only changes its process-local input profile. Keep the keyboard free until the test finishes. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string] $Package,
    [ValidateSet('installed-visible')][string] $Mode = 'installed-visible',
    [string] $BuildRoot = (Join-Path $PSScriptRoot '..\artifacts\.build')
)
$ErrorActionPreference = 'Stop'
$pkg = (Resolve-Path -LiteralPath $Package).Path
$binary = (Resolve-Path -LiteralPath (Join-Path $pkg 'KunKunIME.exe')).Path
$tsf = (Resolve-Path -LiteralPath (Join-Path $pkg 'kunkun_tsf.dll')).Path
$build = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($BuildRoot)
$cache = Join-Path $build 'tsf-x64\CMakeCache.txt'
$settings = if (Test-Path -LiteralPath $cache) { Get-Content -LiteralPath $cache } else { @() }
$cmake = ($settings | Where-Object { $_ -like 'CMAKE_COMMAND:INTERNAL=*' }) -replace '^CMAKE_COMMAND:INTERNAL='
$generator = ($settings | Where-Object { $_ -like 'CMAKE_GENERATOR:INTERNAL=*' }) -replace '^CMAKE_GENERATOR:INTERNAL='
if (-not $cmake) { $cmake = (Get-Command cmake -ErrorAction Stop).Source }
$source = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\tests\windows\richedit')).Path
$probe = Join-Path $build 'richedit'
$genArgs = if ($generator) { @('-G',$generator) } else { @() }
& $cmake -S $source -B $probe @genArgs -A x64
if ($LASTEXITCODE) { throw 'Could not configure RichEdit verification' }
& $cmake --build $probe --config Release --target rich-edit-installed
if ($LASTEXITCODE) { throw 'Could not build RichEdit verification' }
$probeName = 'rich-edit-installed'
$exe = (Resolve-Path (Join-Path $probe "Release\$probeName.exe")).Path
$comKey='Registry::HKEY_LOCAL_MACHINE\SOFTWARE\Classes\CLSID\{D6101A78-E6DB-5FF2-AA53-D4018795C838}\InprocServer32'
$installedPath=(Get-Item -LiteralPath $comKey).GetValue('')
if ((Get-FileHash -LiteralPath $tsf).Hash -ne (Get-FileHash -LiteralPath $installedPath).Hash) { throw 'The registered DLL differs from the package under test.' }
& $exe $binary $Mode
if ($LASTEXITCODE) { throw "RichEdit verification failed in mode $Mode" }

