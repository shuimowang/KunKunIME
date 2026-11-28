# Run using PowerShell on Windows. All writable files are created in a unique temporary directory.
[CmdletBinding()]
param([string]$Package, [string]$TrustedPaths)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$uninstaller = Join-Path $repo 'scripts/uninstall-windows.ps1'
. ([ScriptBlock]::Create([IO.File]::ReadAllText($uninstaller, [Text.Encoding]::UTF8)))
$testParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$testName = 'KunKunIME-uninstall-test-' + [guid]::NewGuid().ToString('N')
$testRoot = Join-Path $testParent $testName
$null = [IO.Directory]::CreateDirectory($testRoot)
$script:KunKunOwnedPaths = @{}
$script:KunKunOwnedHashes = @{}
$fixtureFiles = @('KunKunIME.exe','kunkun_tsf.dll','kunkun_tsf_x86.dll','MCP.md','使用说明.md','卸载Windows输入法注册.md','data/phrases.txt','data_custom/custom.toml','licenses/NOTICE')
foreach ($rel in $fixtureFiles) { $script:KunKunOwnedPaths[$rel] = $true }

function Require([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Rejects([scriptblock]$Code, [string]$Case) {
    try { $null = & $Code } catch { return }
    throw "Unexpected success: $Case"
}

function Write-TestFile([string]$File, [string]$Content) {
    $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($File))
    [IO.File]::WriteAllText($File, $Content, [Text.UTF8Encoding]::new($false))
}

function New-Fixture([string]$Name) {
    $dir = Join-Path $testRoot $Name
    $rows = @(
        foreach ($rel in $fixtureFiles) {
            $path = Join-Path $dir $rel
            Write-TestFile $path ("payload " + $rel)
            $digest = (Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()
            $script:KunKunOwnedHashes[$rel] = $digest
            @{ path = $rel; sha256 = $digest }
        }
    )
    Write-TestFile (Join-Path $dir 'uninstall-manifest.json') (@{ product = 'KunKunIME'; files = $rows } | ConvertTo-Json -Depth 4)
    return (Join-Path $dir 'KunKunIME.exe')
}

function Delete-TestTree([IO.DirectoryInfo]$Directory) {
    # Descend into ordinary test directories only. Delete a junction itself even after an assertion fails.
    foreach ($item in $Directory.GetFileSystemInfos()) {
        if (($item -is [IO.DirectoryInfo]) -and -not ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            Delete-TestTree $item
        } else { $item.Delete() }
    }
    $Directory.Delete()
}

try {
    $exe = New-Fixture 'contents'
    $plan = Get-PackagePlan $exe
    $modified = Join-Path $plan.Root 'data/phrases.txt'
    $foreign = Join-Path $plan.Root 'data/new-personal-phrases.txt'
    Write-TestFile $modified 'user-edited package file'
    Write-TestFile $foreign 'not a package member'
    $kept = @(Remove-OwnedResources $plan)
    Require ($kept -contains 'data/phrases.txt') 'Modified package member not reported'
    Require ([IO.File]::ReadAllText($modified) -eq 'user-edited package file') 'Modified member changed'
    Require ([IO.File]::ReadAllText($foreign) -eq 'not a package member') 'Unlisted file changed'
    Require (-not [IO.File]::Exists((Join-Path $plan.Root 'kunkun_tsf.dll'))) 'Unmodified component remains'
    Require (-not [IO.Directory]::Exists((Join-Path $plan.Root 'licenses'))) 'Empty package directory remains'
    Require ([IO.File]::Exists($exe) -and [IO.File]::Exists($plan.Manifest)) 'Retry inputs removed too early'
    Write-Output 'PASS package resources, user modifications and unlisted personal files'

    $exe = New-Fixture 'invalid'
    $manifestPath = Join-Path (Split-Path -Parent $exe) 'uninstall-manifest.json'
    $original = [IO.File]::ReadAllText($manifestPath)
    foreach ($bad in @('data/../outside','../outside','data//empty','userdata/private.txt','C:/outside','data/file:stream','data/NUL','data/ambiguous.','data/phrases.txt')) {
        $list = $original | ConvertFrom-Json
        $list.files += [pscustomobject]@{ path = $bad; sha256 = ('a' * 64) }
        Write-TestFile $manifestPath ($list | ConvertTo-Json -Depth 4)
        Rejects { Get-PackagePlan $exe } "Invalid manifest path $bad"
    }
    $list = $original | ConvertFrom-Json
    ($list.files | Where-Object path -eq 'KunKunIME.exe').sha256 = '0' * 64
    Write-TestFile $manifestPath ($list | ConvertTo-Json -Depth 4)
    Rejects { Get-PackagePlan $exe } 'Manifest belongs to a different executable'
    $unowned = Join-Path (Split-Path -Parent $exe) 'data/unlisted-personal.txt'
    Write-TestFile $unowned 'personal file added after install'
    $list = $original | ConvertFrom-Json
    $list.files += [pscustomobject]@{ path = 'data/unlisted-personal.txt'; sha256 = (Get-FileHash -LiteralPath $unowned).Hash }
    Write-TestFile $manifestPath ($list | ConvertTo-Json -Depth 4)
    Rejects { Get-PackagePlan $exe } 'A non-product name was added to the local manifest'
    Require ([IO.File]::Exists($unowned)) 'Unowned file was modified during planning'
    $list = $original | ConvertFrom-Json
    $edited = Join-Path (Split-Path -Parent $exe) 'data/phrases.txt'
    Write-TestFile $edited 'changed dictionary data'
    ($list.files | Where-Object path -eq 'data/phrases.txt').sha256 = (Get-FileHash -LiteralPath $edited).Hash
    Write-TestFile $manifestPath ($list | ConvertTo-Json -Depth 4)
    $null = Remove-OwnedResources (Get-PackagePlan $exe)
    Require ([IO.File]::ReadAllText($edited) -eq 'changed dictionary data') 'A modified package-file digest defeated the compiled inventory'
    Write-Output 'PASS invalid paths, duplicate and foreign entries, and mismatched executable identity'

    $exe = New-Fixture 'retry'
    $first = Get-PackagePlan $exe
    $locked = [IO.File]::Open((Join-Path $first.Root 'kunkun_tsf.dll'), 'Open', 'Read', 'None')
    try { Rejects { Remove-OwnedResources $first } 'Locked component' } finally { $locked.Dispose() }
    $retry = Get-PackagePlan $exe
    $null = Remove-OwnedResources $retry
    Require (-not [IO.File]::Exists((Join-Path $first.Root 'kunkun_tsf.dll'))) 'Second removal did not finish'
    Write-Output 'PASS partially completed resource cleanup can be retried'

    $outside = Join-Path $testRoot 'outside'
    $sentinel = Join-Path $outside 'must-survive.txt'
    Write-TestFile $sentinel 'outside the installation'
    $exe = New-Fixture 'junction'
    $plan = Get-PackagePlan $exe
    $oldData = Join-Path $plan.Root 'data'
    [IO.Directory]::Move($oldData, (Join-Path $plan.Root 'detached'))
    $null = New-Item -ItemType Junction -Path $oldData -Target $outside
    Rejects { Get-PackagePlan $exe } 'Junction at a packaged directory before review'
    Rejects { Remove-OwnedResources $plan } 'Junction introduced after review'
    $private = Join-Path $testRoot 'profile-links'
    Write-TestFile (Join-Path $private 'personal.txt') 'keep on rejected cleanup'
    $null = New-Item -ItemType Junction -Path (Join-Path $private 'redirect') -Target $outside
    Rejects { Remove-PrivateTree $private $private } 'Junction below a profile'
    Require ([IO.File]::Exists((Join-Path $private 'personal.txt'))) 'Rejected profile cleanup was partially destructive'
    Require ([IO.File]::ReadAllText($sentinel) -eq 'outside the installation') 'Junction target changed'
    Write-Output 'PASS links in the package or user data do not affect their targets'

    $exe = New-Fixture 'portable'
    $plan = Get-PackagePlan $exe
    Write-TestFile (Join-Path $plan.Root 'portable_mode') ''
    $scope = Get-DataScope $plan $false
    Require ($scope.Portable -and $scope.Local -eq (Join-Path $plan.Root 'localdata') -and $scope.User -eq (Join-Path $plan.Root 'userdata')) 'Incorrect portable scope'
    $userWords = Join-Path $scope.User 'data/novel.txt'
    Write-TestFile $userWords 'portable dictionary'
    Write-TestFile (Join-Path $scope.Local 'cache/session') 'cache'
    Remove-PrivateTree $scope.Local $scope.Local
    Require ([IO.File]::ReadAllText($userWords) -eq 'portable dictionary') 'Keep mode removed portable dictionary'
    Remove-PrivateTree $scope.User $scope.User
    Require (-not [IO.Directory]::Exists($scope.User)) 'Explicit purge left portable dictionary'
    $local = Join-Path $testRoot 'isolated-local-profile'
    $pointer = Join-Path $local 'datadir.conf'
    Write-TestFile $pointer $outside
    Write-TestFile (Join-Path $local 'cache') 'cleanable cache'
    Remove-PrivateTree $local $local 'datadir.conf'
    Require ([IO.File]::ReadAllText($pointer) -eq $outside) 'Keep mode lost a custom profile pointer'
    Remove-PrivateTree $local $local
    Require ([IO.File]::Exists($sentinel)) 'Purge followed an external profile pointer'
    Write-Output 'PASS portable profile keep/purge and external directory sentinel'

    # From here on wizard operations run exclusively against fixtures, including process and registry observations.
    $scopeImplementation = (Get-Command Get-DataScope).ScriptBlock
    $script:roamingRoot = Join-Path $testRoot 'known-roaming'
    $script:localRoot = Join-Path $testRoot 'known-local'
    function Get-DataScope($Plan, [bool]$Foreign) {
        return (& $scopeImplementation $Plan $Foreign -LocalAppData $script:localRoot -RoamingAppData $script:roamingRoot)
    }
    $script:steps = [Collections.Generic.List[string]]::new()
    $script:machineNeeded = $false
    $script:foreignRegistration = $false
    $script:machineExit = 0
    function Invoke-KunKunCli([string]$Exe, [string[]]$CliArgs) {
        Require ($Exe.StartsWith($testRoot+'\',[StringComparison]::OrdinalIgnoreCase)) 'Attempt to launch an unowned executable'
        $operation = $CliArgs -join ' '
        $script:steps.Add($operation)
        switch ($operation) {
            'system register-ime uninstall-status --json' {
                return (@{ machineNeeded=$script:machineNeeded; foreignRegistration=$script:foreignRegistration } | ConvertTo-Json)
            }
            'system uninstall --stop-service' { return }
            'system register-ime unregister-user' { return }
            'system register-ime unregister-associations' { return }
            'system register-ime forget-uninstall-entry' { return }
            default { throw "Unexpected subprocess operation: $operation" }
        }
    }
    function Show-UninstallPrompt($Plan, $Scope, [bool]$Machine) {
        $script:observedScope = $Scope
        if ($script:promptEdit) { Write-TestFile (Join-Path $Plan.Root $script:promptEdit) 'new payload written while review was open' }
        return $script:choice
    }
    function Show-UninstallResult([string]$Message, [bool]$Success) {
        $script:wizardResult = $Success
        $script:wizardMessage = $Message
    }
    function Start-Process($FilePath, $ArgumentList, $Verb, $WindowStyle, [switch]$Wait, [switch]$PassThru) {
        Require ($FilePath -eq $env:KUNKUN_UNINSTALL_EXE -and $Verb -eq 'RunAs') 'Unexpected elevation target'
        Require (($ArgumentList -join ' ') -eq 'system register-ime unregister-machine') 'Unexpected machine operation'
        $script:steps.Add('machine')
        return [pscustomobject]@{ ExitCode=$script:machineExit }
    }
    function Get-Process($Name, $ErrorAction) {
        Require ($Name -eq 'KunKunIME') 'Unexpected process scan'
    }
    # In-memory Windows "Installed apps" key. All registry operations in the wizard terminate here.
    function Get-UninstallRegistryParent {
        if (-not $script:arp) { return $null }
        $key = [pscustomobject]@{}
        $key | Add-Member ScriptMethod OpenSubKey { param($name)
            Require ($name -ceq 'KunKunIME') 'Unexpected app registration key'
            $entry = [pscustomobject]@{}
            $entry | Add-Member ScriptMethod GetValue { param($field) $script:arp[$field] }
            $entry | Add-Member ScriptMethod Dispose {}
            return $entry
        }
        $key | Add-Member ScriptMethod DeleteSubKey { param($name,$throwOnMissing)
            Require ($name -ceq 'KunKunIME') 'Unexpected app registration removal'
            $script:arp = $null
        }
        $key | Add-Member ScriptMethod Dispose {}
        return $key
    }
    $script:arp = $null
    function Run-Wizard([string]$Exe, $Choice) {
        $script:choice = $Choice
        $script:wizardResult = $null
        $script:wizardMessage = ''
        $script:steps.Clear()
        $env:KUNKUN_UNINSTALL_EXE = $Exe
        Start-KunKunUninstall
    }
    function Expect-Completed([string]$Exe) {
        Require ($script:wizardResult -eq $true) "Uninstall did not finish: $script:wizardMessage"
        Require (-not [IO.File]::Exists($Exe)) 'Successful uninstall left the executable'
        Require (-not [IO.File]::Exists((Join-Path (Split-Path -Parent $Exe) 'uninstall-manifest.json'))) 'Successful uninstall left the manifest'
    }

    $exe = New-Fixture 'wizard-cancel'
    Run-Wizard $exe $null
    Require ($null -eq $script:wizardResult -and [IO.File]::Exists($exe)) 'Cancel mutated the installation'
    Require ($steps.Count -eq 1 -and $steps[0] -eq 'system register-ime uninstall-status --json') 'Cancel triggered a write action'

    foreach ($file in @('KunKunIME.exe','uninstall-manifest.json')) {
        $exe = New-Fixture ("wizard-changed-" + $file)
        $script:promptEdit = $file
        try { Run-Wizard $exe $true } finally { $script:promptEdit = $null }
        Require ($script:wizardResult -eq $false -and $steps.Count -eq 1) 'A changed executable or manifest was used after confirmation'
        Require ([IO.File]::Exists((Join-Path (Split-Path -Parent $exe) 'kunkun_tsf.dll'))) 'Confirmation removed files from a newly changed package'
        $exe = New-Fixture ("wizard-final-changed-" + $file)
        $plan = Get-PackagePlan $exe
        Write-TestFile (Join-Path $plan.Root $file) 'new payload from a concurrent installer'
        Rejects { Complete-Uninstall $plan } 'Package identity changed before final removal'
        Require ([IO.File]::Exists($plan.Exe) -and [IO.File]::Exists($plan.Manifest)) 'Final removal deleted a different executable or manifest'
    }
    Write-Output 'PASS executable and manifest are rechecked after the prompt and before the last deletion'

    $exe = New-Fixture 'wizard-concurrent'
    $rootBytes = [Text.Encoding]::UTF8.GetBytes((Split-Path -Parent $exe).ToUpperInvariant())
    $hash = [Security.Cryptography.SHA256]::Create()
    try { $suffix = [BitConverter]::ToString($hash.ComputeHash($rootBytes)).Replace('-','') } finally { $hash.Dispose() }
    $ready = [Threading.ManualResetEvent]::new($false)
    $release = [Threading.ManualResetEvent]::new($false)
    $worker = [PowerShell]::Create()
    try {
        $null = $worker.AddScript({
            param($name,$ready,$release)
            $handle = [Threading.Mutex]::new($false,$name)
            try {
                if (-not $handle.WaitOne(5000)) { throw 'Could not hold the per-install mutex' }
                try { $null = $ready.Set(); $null = $release.WaitOne(15000) } finally { $handle.ReleaseMutex() }
            } finally { $handle.Dispose() }
        }).AddArgument("Local\KunKunIME-Uninstall-$suffix").AddArgument($ready).AddArgument($release)
        $job = $worker.BeginInvoke()
        Require ($ready.WaitOne(5000)) 'Could not initialize concurrent-wizard fixture'
        Run-Wizard $exe $true
        Require ($script:wizardResult -eq $false -and $steps.Count -eq 0 -and [IO.File]::Exists($exe)) 'Duplicate wizard mutated this installation'
    } finally {
        $null = $release.Set()
        if ($job) { $null = $worker.EndInvoke($job) }
        $worker.Dispose(); $ready.Dispose(); $release.Dispose()
    }
    Write-Output 'PASS concurrently opened wizards for a single installation'

    $exe = New-Fixture 'wizard-machine-retry'
    $script:machineNeeded = $true
    $script:machineExit = 1
    Run-Wizard $exe $true
    Require ($script:wizardResult -eq $false -and [IO.File]::Exists($exe)) 'Machine failure removed the retry entry'
    Require ([IO.File]::Exists((Join-Path (Split-Path -Parent $exe) 'data/phrases.txt'))) 'Machine failure removed the package'
    $script:machineExit = 0
    Run-Wizard $exe $true
    Expect-Completed $exe
    Require ($steps -contains 'machine' -and $steps -contains 'system register-ime unregister-user') 'Retry skipped requested registration cleanup'
    $script:machineNeeded = $false
    Write-Output 'PASS wizard cancellation and failed machine removal followed by a successful retry'

    $exe = New-Fixture 'wizard-exe-lock'
    $manifestPath = Join-Path (Split-Path -Parent $exe) 'uninstall-manifest.json'
    $manifestHash = (Get-FileHash -LiteralPath $manifestPath).Hash
    $script:arp = @{ InstallLocation=(Split-Path -Parent $exe); UninstallString=('"'+$exe+'" system uninstall') }
    $locked = [IO.File]::Open($exe,'Open','Read','Read')
    try { Run-Wizard $exe $false } finally { $locked.Dispose() }
    Require ($script:wizardResult -eq $false -and [IO.File]::Exists($exe) -and $script:arp.Count -eq 2) 'Last-file failure dropped the uninstall entry'
    Require ((Get-FileHash -LiteralPath $manifestPath).Hash -eq $manifestHash) 'Failed final step lost the retry manifest'
    Run-Wizard $exe $false
    Expect-Completed $exe
    Require ($null -eq $script:arp) 'Success left the owned uninstall entry'
    Write-Output 'PASS locked executable retains its manifest and uninstall entry until retry succeeds'

    foreach ($purge in @($false,$true)) {
        $exe = New-Fixture "wizard-portable-$purge"
        $installDir = Split-Path -Parent $exe
        $marker = Join-Path $installDir 'portable_mode'
        $words = Join-Path $installDir 'userdata/data/novel.txt'
        Write-TestFile $marker ''
        Write-TestFile $words 'portable dictionary'
        Run-Wizard $exe $purge
        Expect-Completed $exe
        Require ($observedScope.Portable -and [IO.File]::Exists($words) -eq (-not $purge)) 'Incorrect portable dictionary scope'
        Require ([IO.File]::Exists($marker) -eq (-not $purge)) 'Reinstallation would lose its portable mode'
    }
    $localProfile = Join-Path $localRoot 'KunKunIME'
    $userProfile = Join-Path $roamingRoot 'KunKunIME'
    $actualPointer = Join-Path $localProfile 'datadir.conf'
    foreach ($purge in @($false,$true)) {
        $exe = New-Fixture "wizard-custom-$purge"
        Write-TestFile $actualPointer $outside
        $words = Join-Path $userProfile 'data/old.txt'
        Write-TestFile $words 'legacy default-profile data'
        Run-Wizard $exe $purge
        Expect-Completed $exe
        Require (-not $observedScope.Portable -and $observedScope.External -eq $outside) 'Wizard did not identify the redirected profile'
        Require ([IO.File]::Exists($actualPointer) -eq (-not $purge)) 'Wrong retention for the external profile pointer'
        Require ([IO.File]::Exists($words) -eq (-not $purge)) 'Wrong disposition of the default profile'
        Require ([IO.File]::ReadAllText($sentinel) -eq 'outside the installation') 'External data changed'
    }
    foreach ($ownedRoot in @($localProfile,$userProfile)) {
        $nested = Join-Path $ownedRoot 'my-custom-dictionary'
        $words = Join-Path $nested 'data/novel.txt'
        Write-TestFile $words 'protected custom dictionary'
        Write-TestFile $actualPointer $nested
        $exe = New-Fixture ("wizard-nested-" + (Split-Path -Leaf (Split-Path -Parent $ownedRoot)))
        Run-Wizard $exe $true
        Expect-Completed $exe
        Require ([IO.File]::ReadAllText($words) -eq 'protected custom dictionary') 'Purge followed a custom directory nested inside a default directory'
    }
    $exe = New-Fixture 'wizard-custom-at-install'
    $installDir = Split-Path -Parent $exe
    $words = Join-Path $installDir 'writing/userdict.txt'
    Write-TestFile $actualPointer $installDir
    Write-TestFile $words 'external profile in installation directory'
    Run-Wizard $exe $true
    Expect-Completed $exe
    Require ([IO.File]::ReadAllText($words) -eq 'external profile in installation directory') 'User files in a shared installation directory changed'
    Write-Output 'PASS wizard keep/purge for portable, redirected and nested user profiles'

    foreach ($installRelative in @('known-local/KunKunIME','known-local/KunKunIME/app-folder')) {
        $exe = New-Fixture $installRelative
        $notes = Join-Path $localProfile 'unrelated-notes.txt'
        $inside = Join-Path (Split-Path -Parent $exe) 'not-in-the-package.txt'
        Write-TestFile $notes 'unrelated notes in the shared parent'
        Write-TestFile $inside 'unlisted file next to the program'
        Run-Wizard $exe $true
        Expect-Completed $exe
        Require ([IO.File]::ReadAllText($notes) -eq 'unrelated notes in the shared parent') 'Cleaning an overlapping local profile lost unrelated notes'
        Require ([IO.File]::ReadAllText($inside) -eq 'unlisted file next to the program') 'Cleaning an overlapping local profile recursively deleted the installation'
    }
    $savedRoaming = $script:roamingRoot
    try {
        $script:roamingRoot = $script:localRoot
        $words = Join-Path $localProfile 'data/merged-dictionary.txt'
        Write-TestFile $words 'words in a merged user and cache directory'
        $exe = New-Fixture 'wizard-merged-profile'
        Run-Wizard $exe $false
        Expect-Completed $exe
        Require ([IO.File]::ReadAllText($words) -eq 'words in a merged user and cache directory') 'Cache cleanup removed a merged roaming dictionary in keep mode'
    } finally { $script:roamingRoot = $savedRoaming }
    Write-Output 'PASS overlap between the install directory, application cache and roaming profile'

    $exe = New-Fixture 'wizard-foreign'
    $words = Join-Path $userProfile 'other-installation.txt'
    Write-TestFile $words 'dictionary belonging to another installation'
    $script:foreignRegistration = $true
    $script:arp = @{ InstallLocation=$outside; UninstallString='unrelated' }
    Run-Wizard $exe $true
    Expect-Completed $exe
    Require ($observedScope.Shared -and [IO.File]::Exists($words)) 'Another installation lost user data'
    Require ($steps -notcontains 'machine' -and $steps -notcontains 'system register-ime unregister-user') 'Another installation received an unregister request'
    Require ($steps -contains 'system register-ime unregister-associations') 'Secondary uninstall skipped its own optional associations'
    Require ($script:arp.InstallLocation -eq $outside) 'A different install lost its uninstall entry'
    Write-Output 'PASS uninstalling a secondary copy preserves the registered installation'

    if ($Package) {
        # Validate the supplied distribution in place, read-only. Cleanup functions are never called on it.
        $snapshot = (Resolve-Path -LiteralPath $Package).Path
        if (-not $TrustedPaths) { throw 'Pass the independently generated build inventory with -TrustedPaths.' }
        $script:KunKunOwnedPaths = @{}
        $script:KunKunOwnedHashes = @{}
        $listPath = (Resolve-Path -LiteralPath $TrustedPaths).Path
        foreach ($rel in [IO.File]::ReadAllLines($listPath)) { $script:KunKunOwnedPaths[$rel] = $true }
        foreach ($table in @('uninstall-owned-sha256.txt','uninstall-owned-alternate-sha256.txt')) {
            $hashPath = Join-Path ([IO.Path]::GetDirectoryName($listPath)) $table
            foreach ($line in [IO.File]::ReadAllLines($hashPath)) {
                $digest, $rel = $line -split "`t", 2
                $script:KunKunOwnedHashes[$rel] = @($script:KunKunOwnedHashes[$rel]) + $digest
            }
        }
        $packagedPlan = Get-PackagePlan (Join-Path $snapshot 'KunKunIME.exe')
        foreach ($entry in $packagedPlan.Entries.Values) {
            $digest = (Get-FileHash -LiteralPath $entry.Path).Hash
            Require ($digest -eq $entry.Hash) "Package hash mismatch: $($entry.Path)"
            Require (-not $entry.TrustedHash -or $digest -in @($entry.TrustedHash)) "Packaged file disagrees with the compiled inventory: $($entry.Path)"
        }
        $items = @(Get-ChildItem -LiteralPath $snapshot -File -Recurse -Force)
        Require ($items.Count -eq $packagedPlan.Entries.Count + 1) 'Package inventory is incomplete'
        Write-Output "PASS packaged snapshot and all $($packagedPlan.Entries.Count) manifest entries"
    }
} finally {
    $rootInfo = [IO.DirectoryInfo]::new($testRoot)
    if ($rootInfo.Parent.FullName -ne $testParent -or $rootInfo.Name -ne $testName -or
        ($rootInfo.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Unsafe test cleanup path' }
    Delete-TestTree $rootInfo
}
