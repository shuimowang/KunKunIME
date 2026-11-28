# 在 Windows 撤销困困输入法注册

带卸载向导的版本请优先打开 **设置 → 关于 → 卸载困困输入法…**，或在 Windows「已安装的应用」中选择困困输入法。向导可以移除当前账户的输入法选项、属于此安装的系统注册和程序文件。勾选删除个人数据后才会清除个人词库与设置。下列步骤供旧版本手动撤销注册时使用。

如果手动在 AI 客户端配置过困困的 MCP 服务，可以在该客户端删除对应条目。单独安装的 Quicker 动作由 Quicker 管理。

## 在使用输入法的账户中

先按 **Win + 空格** 切到另一款键盘。打开 Windows 设置里的 **时间和语言 → 语言和区域 → 中文（简体）的语言选项 → 键盘**，在「困困输入法」右侧选「删除」。这一步管理当前账户的输入法列表。

## 在 64 位管理员 PowerShell 中

以下命令对应通过困困界面完成的标准安装。先由两个体系结构各自的 `regsvr32` 调用困困 DLL 的 `DllUnregisterServer`，再清理本产品记录的安装位置。各用户的词库和设置继续保存在各自的数据目录。

```powershell
$components = @(
    @('System32', 'kunkun_tsf.dll'),
    @('SysWOW64', 'kunkun_tsf_x86.dll')
)
foreach ($component in $components) {
    $systemDir = Join-Path $env:SystemRoot $component[0]
    $dll = Join-Path $systemDir ('IME\KunKunIME\' + $component[1])
    if (Test-Path -LiteralPath $dll) {
        $process = Start-Process (Join-Path $systemDir 'regsvr32.exe') -ArgumentList @('/u', '/s', ('"' + $dll + '"')) -WindowStyle Hidden -Wait -PassThru
        if ($process.ExitCode -ne 0) { throw "注销失败（$($process.ExitCode)）：$dll" }
    }
}
$clsid = '{D6101A78-E6DB-5FF2-AA53-D4018795C838}'
foreach ($view in @([Microsoft.Win32.RegistryView]::Registry64, [Microsoft.Win32.RegistryView]::Registry32)) {
    $hklm = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::LocalMachine, $view)
    try {
        $tip = $hklm.OpenSubKey("SOFTWARE\Classes\CLSID\$clsid\InprocServer32")
        if ($tip) { $tip.Dispose(); throw "$view 的困困组件仍已注册，保留安装位置供检查。" }
    } finally { $hklm.Dispose() }
}
$key = 'HKLM:\Software\KunKunIME'
if (Test-Path -LiteralPath $key) {
    Remove-ItemProperty -LiteralPath $key -Name InstallDir -ErrorAction SilentlyContinue
}
```

在同一份软件目录运行 `KunKunIME.exe system register-ime status --json` 检查状态。程序自动化读取输出时先设置 `$env:WIND_INPUT_CLI_PIPED='1'`；GUI EXE 的输出可通过管道接收。

