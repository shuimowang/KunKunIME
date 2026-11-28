# Embedded in KunKunIME.exe. Dot-source for the isolated file-cleanup tests.
function Get-PackageHash([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    try {
        $sha = [Security.Cryptography.SHA256]::Create()
        try { return [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','') }
        finally { $sha.Dispose() }
    } finally { $stream.Dispose() }
}

function Assert-PlainPath([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    $current = $full
    while ($current) {
        $entry = Get-Item -LiteralPath $current -Force -ErrorAction SilentlyContinue
        if ($entry -and ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "路径使用了链接或目录联接，已停止清理：$current"
        }
        $current = [IO.Path]::GetDirectoryName($current)
    }
    return $full
}

function Get-PackagePlan([string]$Exe) {
    $exePath = Assert-PlainPath $Exe
    $root = [IO.Path]::GetDirectoryName($exePath)
    if ([IO.Path]::GetFileName($exePath) -ne 'KunKunIME.exe' -or $root -eq [IO.Path]::GetPathRoot($root)) {
        throw '无法确认困困输入法安装目录。'
    }
    $manifest = Assert-PlainPath (Join-Path $root 'uninstall-manifest.json')
    if (-not [IO.File]::Exists($manifest)) { throw '缺少卸载清单，请将提供卸载功能的完整程序包解压到原目录，再试一次。' }
    $list = [IO.File]::ReadAllText($manifest) | ConvertFrom-Json -ErrorAction Stop
    if ($list.product -cne 'KunKunIME' -or -not $list.files.Count) { throw '卸载清单不属于困困输入法。' }
    $top = @('KunKunIME.exe','kunkun_tsf.dll','kunkun_tsf_x86.dll','使用说明.md','MCP.md','卸载Windows输入法注册.md')
    $entries = @{}
    foreach ($file in $list.files) {
        $rel = [string]$file.path
        $parts = @($rel -split '/')
        if ($rel -match '[\\:*?"<>|\x00-\x1f]' -or
            @($parts | Where-Object { -not $_ -or $_ -in @('.','..') -or $_ -match '[ .]$|^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\.|$)' }).Count -or
            ($rel -notin $top -and ($parts.Count -lt 2 -or $parts[0] -notin @('data','data_custom','licenses'))) -or
            $file.sha256 -notmatch '^[a-fA-F0-9]{64}$' -or $entries.ContainsKey($rel)) {
            throw "卸载清单含无效或重复的文件名：$rel"
        }
        $full = Assert-PlainPath (Join-Path $root $rel)
        if (-not $full.StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase)) { throw "文件超出安装目录：$rel" }
        $notice = $rel -cmatch '^licenses/rust/texts/([a-f0-9]{64})\.txt$'
        $trustedHash = if ($notice) { $Matches[1] } elseif ($script:KunKunOwnedHashes) { $script:KunKunOwnedHashes[$rel] }
        if (-not $script:KunKunOwnedPaths -or (-not $script:KunKunOwnedPaths.ContainsKey($rel) -and -not $notice)) {
            throw "卸载清单包含本程序没有分发的文件：$rel"
        }
        $entries[$rel] = [pscustomobject]@{ Path=$full; Hash=$file.sha256; TrustedHash=$trustedHash }
    }
    foreach ($owned in $script:KunKunOwnedPaths.Keys) {
        if (-not $entries.ContainsKey($owned)) { throw "卸载清单不完整，缺少：$owned" }
    }
    if (-not $entries.ContainsKey('KunKunIME.exe') -or -not $entries.ContainsKey('data_custom/custom.toml') -or
        (Get-PackageHash $exePath) -ne $entries['KunKunIME.exe'].Hash) {
        throw '卸载清单与当前程序不匹配，请重新解压同一版本的完整程序包。'
    }
    [pscustomobject]@{ Root=$root; Exe=$exePath; Manifest=$manifest;
        ManifestHash=(Get-PackageHash $manifest); Entries=$entries }
}

function Remove-EmptyDirectory([string]$Path) {
    if ([IO.Directory]::Exists($Path) -and @(Get-ChildItem -LiteralPath $Path -Force).Count -eq 0) {
        [IO.Directory]::Delete((Assert-PlainPath $Path), $false)
    }
}

function Remove-OwnedResources($Plan) {
    $folders = @{}
    $kept = @()
    foreach ($rel in $Plan.Entries.Keys) {
        if ($rel -eq 'KunKunIME.exe') { continue }
        $entry = $Plan.Entries[$rel]
        $path = Assert-PlainPath $entry.Path
        $dir = [IO.Path]::GetDirectoryName($path)
        while ($dir -ne $Plan.Root) {
            $folders[$dir] = $true
            $dir = [IO.Path]::GetDirectoryName($dir)
        }
        if (-not [IO.File]::Exists($path)) { continue }
        $actual = Get-PackageHash $path
        if ($actual -eq $entry.Hash -and (-not $entry.TrustedHash -or $actual -in @($entry.TrustedHash))) {
            [IO.File]::Delete($path)
        } else { $kept += $rel }
    }
    foreach ($dir in ($folders.Keys | Sort-Object Length -Descending)) { Remove-EmptyDirectory $dir }
    return $kept
}

function Remove-PrivateTree([string]$Path, [string]$Expected, [string]$PreserveName = '') {
    $normal = Assert-PlainPath $Path
    if ($normal -ne [IO.Path]::GetFullPath($Expected) -or $normal -eq [IO.Path]::GetPathRoot($normal)) { throw "无效的数据目录：$normal" }
    if (-not [IO.Directory]::Exists($normal)) { return }
    # Inventory first, including hidden items. Never traverse a junction into another directory.
    $stack = New-Object 'System.Collections.Generic.Stack[string]'
    $directories = New-Object 'System.Collections.Generic.List[string]'
    $files = New-Object 'System.Collections.Generic.List[string]'
    $stack.Push($normal)
    while ($stack.Count) {
        $dir = $stack.Pop()
        $directories.Add($dir)
        foreach ($item in (Get-ChildItem -LiteralPath (Assert-PlainPath $dir) -Force -ErrorAction Stop)) {
            if ($dir -eq $normal -and $item.Name -eq $PreserveName) { continue }
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "数据目录含链接，已保留：$($item.FullName)" }
            if (-not $item.FullName.StartsWith($normal+'\',[StringComparison]::OrdinalIgnoreCase)) { throw '子项超出数据目录。' }
            if ($item.PSIsContainer) { $stack.Push($item.FullName) } else { $files.Add($item.FullName) }
        }
    }
    foreach ($f in $files) { [IO.File]::Delete((Assert-PlainPath $f)) }
    foreach ($dir in ($directories | Sort-Object Length -Descending)) { Remove-EmptyDirectory $dir }
}

function Get-DataScope($Plan, [bool]$Foreign,
        [string]$LocalAppData = [Environment]::GetFolderPath('LocalApplicationData'),
        [string]$RoamingAppData = [Environment]::GetFolderPath('ApplicationData')) {
    $markers = @('portable_mode','wind_portable_mode') | Where-Object { [IO.File]::Exists((Join-Path $Plan.Root $_)) }
    if ($markers) {
        return [pscustomobject]@{ Portable=$true; Shared=$false; External=''; Local=(Join-Path $Plan.Root 'localdata'); User=(Join-Path $Plan.Root 'userdata'); Markers=@($markers) }
    }
    $local = Join-Path $LocalAppData 'KunKunIME'
    $roaming = Join-Path $RoamingAppData 'KunKunIME'
    $external = ''
    $pointer = Join-Path $local 'datadir.conf'
    if (-not $Foreign -and [IO.File]::Exists($pointer)) {
        $custom = [IO.File]::ReadAllText((Assert-PlainPath $pointer)).Trim()
        if ($custom -and $custom -ne $roaming) { $external = $custom }
    }
    [pscustomobject]@{ Portable=$false; Shared=$Foreign; External=$external; Local=$local; User=$roaming; Markers=@() }
}

function Invoke-KunKunCli([string]$Exe, [string[]]$CliArgs) {
    $text = & $Exe @CliArgs 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "操作未完成：$text" }
    return $text
}

function Show-UninstallPrompt($Plan, $Scope, [bool]$Machine) {
    $form = New-Object Windows.Forms.Form
    $form.Text = '卸载困困输入法'
    $form.StartPosition = 'CenterScreen'
    $form.AutoScaleMode = 'Dpi'
    $form.ClientSize = New-Object Drawing.Size(540,270)
    $form.FormBorderStyle = 'FixedDialog'
    $form.MaximizeBox = $false
    $form.MinimizeBox = $false
    $form.Font = New-Object Drawing.Font('Microsoft YaHei UI',9)
    $label = New-Object Windows.Forms.Label
    $label.SetBounds(18,14,504,112)
    $label.Text = "将关闭困困输入法，并移除以下目录中的程序文件：`n$($Plan.Root)"
    if ($Machine) { $label.Text += "`n`n还将移除此电脑上的困困输入法注册。Windows 可能会请求管理员授权。" }
    if ($Scope.Shared) { $label.Text += "`n`n另一份困困输入法正在使用 Windows 注册，此处只卸载当前副本。" }
    $form.Controls.Add($label)
    $purge = New-Object Windows.Forms.CheckBox
    $purge.SetBounds(18,130,504,30)
    $purge.Text = '同时永久删除本账户的个人词库、词频和设置'
    $purge.Enabled = -not $Scope.Shared
    $form.Controls.Add($purge)
    $note = New-Object Windows.Forms.Label
    $note.SetBounds(18,165,504,48)
    $note.Text = '默认保留个人数据，重新安装后可以继续使用。'
    if ($Scope.External) { $note.Text += "`n自定义位置会保留，请自行管理：$($Scope.External)" }
    $form.Controls.Add($note)
    $cancel = New-Object Windows.Forms.Button
    $cancel.Text = '取消'; $cancel.SetBounds(325,223,92,32); $cancel.DialogResult = 'Cancel'
    $ok = New-Object Windows.Forms.Button
    $ok.Text = '卸载'; $ok.SetBounds(427,223,92,32); $ok.DialogResult = 'OK'
    $form.Controls.AddRange(@($cancel,$ok)); $form.CancelButton = $cancel; $form.AcceptButton = $cancel
    try {
        if ($form.ShowDialog() -ne 'OK') { return $null }
        return [bool]$purge.Checked
    } finally { $form.Dispose() }
}

function Show-UninstallResult([string]$Message, [bool]$Success) {
    if ($Success) { [void][Windows.Forms.MessageBox]::Show($Message,'卸载完成','OK','Information') }
    else { [void][Windows.Forms.MessageBox]::Show($Message,'卸载未完成','OK','Warning') }
}

function Test-DataOverlap([string]$Directory, [string]$External) {
    if (-not $External) { return $false }
    if (-not [IO.Path]::IsPathRooted($External)) { return $true }
    $candidate = [IO.Path]::GetFullPath($Directory).TrimEnd('\') + '\'
    $externalDir = [IO.Path]::GetFullPath($External).TrimEnd('\') + '\'
    return $candidate.StartsWith($externalDir,[StringComparison]::OrdinalIgnoreCase) -or
        $externalDir.StartsWith($candidate,[StringComparison]::OrdinalIgnoreCase)
}

function Get-UninstallRegistryParent {
    return [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Software\Microsoft\Windows\CurrentVersion\Uninstall',$true)
}

function Confirm-PackageIdentity($Plan) {
    if ((Get-PackageHash (Assert-PlainPath $Plan.Exe)) -ne $Plan.Entries['KunKunIME.exe'].Hash -or
        (Get-PackageHash (Assert-PlainPath $Plan.Manifest)) -ne $Plan.ManifestHash) {
        throw '卸载期间程序或清单已更新，请从当前安装目录重新打开向导。'
    }
}

function Test-ContainsInstall([string]$Directory, [string]$InstallRoot) {
    $dir = [IO.Path]::GetFullPath($Directory).TrimEnd('\') + '\'
    return ($InstallRoot.TrimEnd('\')+'\').StartsWith($dir,[StringComparison]::OrdinalIgnoreCase)
}

function Test-InstallWriteAccess([string]$Root) {
    $probe = Join-Path (Assert-PlainPath $Root) ('.kunkun-uninstall-'+[guid]::NewGuid().ToString('N')+'.tmp')
    try {
        $handle = [IO.File]::Open($probe,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        $handle.Dispose()
        [IO.File]::Delete($probe)
    } catch {
        throw "当前账户没有修改安装目录的权限。请取得此目录的修改权限后重试：$Root"
    }
}

function Complete-Uninstall($Plan) {
    # Check access before removing the last executable. Keep the entry and manifest usable on failure.
    $parent = Get-UninstallRegistryParent
    try {
        Confirm-PackageIdentity $Plan
        $bytes = [IO.File]::ReadAllBytes((Assert-PlainPath $Plan.Manifest))
        [IO.File]::Delete($Plan.Manifest)
        try { [IO.File]::Delete((Assert-PlainPath $Plan.Exe)) }
        catch {
            [IO.File]::WriteAllBytes($Plan.Manifest, $bytes)
            throw
        }
        if ($parent) {
            $entry = $parent.OpenSubKey('KunKunIME')
            if ($entry) {
                try {
                    $ours = $entry.GetValue('InstallLocation') -eq $Plan.Root -and
                        $entry.GetValue('UninstallString') -eq ('"' + $Plan.Exe + '" system uninstall')
                } finally { $entry.Dispose() }
                if ($ours) { $parent.DeleteSubKey('KunKunIME',$false) }
            }
        }
    } finally { if ($parent) { $parent.Dispose() } }
}

function Start-KunKunUninstall {
    $ErrorActionPreference = 'Stop'
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    [Windows.Forms.Application]::EnableVisualStyles()
    $env:WIND_INPUT_CLI_PIPED = '1'
    $mutex = $null
    $ownsMutex = $false
    try {
        $plan = Get-PackagePlan $env:KUNKUN_UNINSTALL_EXE
        $hash = [Security.Cryptography.SHA256]::Create()
        try {
            $name = [BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($plan.Root.ToUpperInvariant()))).Replace('-','')
        } finally { $hash.Dispose() }
        $mutex = [Threading.Mutex]::new($false, "Local\KunKunIME-Uninstall-$name")
        try { $ownsMutex = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $ownsMutex = $true }
        if (-not $ownsMutex) { throw '这个安装目录已打开卸载向导。' }
        $statusArgs = @('system','register-ime','uninstall-status','--json')
        $status = (Invoke-KunKunCli $plan.Exe $statusArgs) | ConvertFrom-Json
        $scope = Get-DataScope $plan $status.foreignRegistration
        $erase = Show-UninstallPrompt $plan $scope $status.machineNeeded
        if ($null -eq $erase) { return }
        Confirm-PackageIdentity $plan
        Test-InstallWriteAccess $plan.Root
        $now = (Invoke-KunKunCli $plan.Exe $statusArgs) | ConvertFrom-Json
        if ($status.foreignRegistration -ne $now.foreignRegistration -or $status.machineNeeded -ne $now.machineNeeded) {
            throw 'Windows 输入法注册发生变化，请重新打开卸载向导。'
        }
        # Ask only settings windows belonging to this exact installation to save and close.
        $localWindows = Get-Process -Name KunKunIME -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $plan.Exe -and $_.MainWindowHandle }
        foreach ($p in $localWindows) {
            if (-not $p.CloseMainWindow() -or -not $p.WaitForExit(6000)) { throw '请先关闭困困输入法设置窗口再重试。' }
        }
        if (-not $status.foreignRegistration) {
            $null = Invoke-KunKunCli $plan.Exe @('system','register-ime','unregister-user')
        }
        if (-not $status.foreignRegistration -and $status.machineNeeded) {
            try {
                $machine = Start-Process -FilePath $plan.Exe -ArgumentList @('system','register-ime','unregister-machine') -Verb RunAs -WindowStyle Hidden -Wait -PassThru
            } catch {
                throw "当前账户的困困输入法选项已移除，程序文件仍在。Windows 未完成系统组件卸载，请重新打开向导。$($_.Exception.Message)"
            }
            if ($machine.ExitCode -ne 0) { throw 'Windows 输入法组件未能全部移除。请关闭正在使用它的应用，再从同一目录重试。' }
        }
        $null = Invoke-KunKunCli $plan.Exe @('system','register-ime','unregister-associations')
        $null = Invoke-KunKunCli $plan.Exe @('system','uninstall','--stop-service')
        $deadline = [DateTime]::UtcNow.AddSeconds(8)
        do {
            $busy = @(Get-Process -Name KunKunIME -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $plan.Exe })
            if ($busy.Count) { Start-Sleep -Milliseconds 180 }
        } while ($busy.Count -and [DateTime]::UtcNow -lt $deadline)
        if ($busy.Count) { throw '困困输入法仍有客户端在使用（例如 MCP）。请关闭关联客户端后重试。' }

        $kept = @(Remove-OwnedResources $plan)
        $sharedData = @()
        if (-not $scope.Shared) {
            # datadir.conf is needed to find a preserved dictionary on reinstall.
            $preserve = if ($erase) { '' } else { 'datadir.conf' }
            if ((Test-DataOverlap $scope.Local $scope.External) -or (Test-ContainsInstall $scope.Local $plan.Root) -or
                (-not $erase -and (Test-DataOverlap $scope.Local $scope.User))) {
                $sharedData += $scope.Local
            } else { Remove-PrivateTree $scope.Local $scope.Local $preserve }
            if ($erase) {
                if ((Test-DataOverlap $scope.User $scope.External) -or (Test-ContainsInstall $scope.User $plan.Root)) {
                    $sharedData += $scope.User
                } else { Remove-PrivateTree $scope.User $scope.User }
            }
        }
        if ($erase -or -not [IO.Directory]::Exists($scope.User)) {
            foreach ($marker in $scope.Markers) { [IO.File]::Delete((Assert-PlainPath (Join-Path $plan.Root $marker))) }
        }
        Complete-Uninstall $plan
        # Never remove a user-selected install parent such as Desktop or Downloads.
        if ([IO.Path]::GetFileName($plan.Root) -in @('困困输入法','KunKunIME')) { Remove-EmptyDirectory $plan.Root }
        $message = '困困输入法已卸载。'
        if (-not $erase -and -not $scope.Shared) { $message += "`n个人词库与设置已保留。" }
        if ($scope.External) { $message += "`n自定义数据目录已保留：$($scope.External)" }
        foreach ($dir in ($sharedData | Select-Object -Unique)) { $message += "`n与其他内容共用的目录已保留：$dir" }
        if ($kept.Count) { $message += "`n已保留 $($kept.Count) 个修改过的随包文件。" }
        Show-UninstallResult $message $true
    } catch {
        Show-UninstallResult $_.Exception.Message $false
    } finally {
        if ($ownsMutex) { $mutex.ReleaseMutex() }
        if ($mutex) { $mutex.Dispose() }
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    $script:KunKunOwnedPaths = @{}
    foreach ($line in $packagePaths.Split([char]10)) {
        if ($line.Trim()) { $script:KunKunOwnedPaths[$line.Trim()] = $true }
    }
    $script:KunKunOwnedHashes = @{}
    foreach ($line in $packageHashes.Split([char]10)) {
        if (-not $line.Trim()) { continue }
        $digest, $name = $line.Trim().Split([char]9,2)
        $script:KunKunOwnedHashes[$name] = @($script:KunKunOwnedHashes[$name]) + $digest
    }
    Start-KunKunUninstall
}
