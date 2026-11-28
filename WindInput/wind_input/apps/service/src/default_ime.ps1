# Embedded in KunKunIME.exe; $operation, $ourTip and $registered come from register_ime.rs.
function Read-DefaultInput {
    $preference = Get-WinDefaultInputMethodOverride
    $inputTip = $null
    $name = $null
    $mode = 'auto'
    if ($null -ne $preference) {
        $inputTip = [string]$preference.InputMethodTip
        if ([string]::IsNullOrWhiteSpace($inputTip)) {
            throw 'Windows 返回了无法识别的默认输入法。'
        }
        $name = $preference.Description
        $mode = if ($inputTip -eq $ourTip) { 'kunkun' } else { 'other' }
    }
    $enabled = $false
    if ($registered) {
        foreach ($language in Get-WinUserLanguageList) {
            if ($language.InputMethodTips -contains $ourTip) {
                $enabled = $true
                break
            }
        }
    }
    [pscustomobject]@{mode=$mode; inputTip=$inputTip; name=$name; registered=$registered; enabled=$enabled}
}

try {
    $state = Read-DefaultInput
    switch ($operation) {
        'set-default' {
            if (!$state.registered -or !$state.enabled) {
                throw '请先注册困困输入法，并将它加入当前用户的输入法列表。'
            }
            if ($state.mode -ne 'kunkun') {
                Set-WinDefaultInputMethodOverride -InputTip $ourTip
                $state = Read-DefaultInput
                if ($state.mode -ne 'kunkun') { throw 'Windows 未确认困困已设为默认输入法。' }
            }
        }
        { $_ -in 'reset-default','release-own-default' } {
            if ($state.mode -eq 'other' -and $operation -eq 'reset-default') {
                throw '当前固定的是其他输入法。请刷新页面查看最新状态。'
            }
            if ($state.mode -eq 'kunkun') {
                # Omitting InputTip selects the cmdlet's restore-from-language-list branch.
                Set-WinDefaultInputMethodOverride
                $state = Read-DefaultInput
                if ($state.mode -ne 'auto') { throw 'Windows 未确认已恢复自动选择输入法。' }
            }
        }
        'default-status' {}
        default { throw '未知的默认输入法操作。' }
    }
    [Console]::Out.WriteLine(($state | ConvertTo-Json -Compress))
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
