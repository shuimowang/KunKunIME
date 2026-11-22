[CmdletBinding()]
param(
    [string] $Package = (Join-Path $PSScriptRoot '..\artifacts\windows'),
    [switch] $CheckProcess
)
$ErrorActionPreference = 'Stop'
$packageDir = (Resolve-Path -LiteralPath $Package).Path
if (-not $CheckProcess) {
    if (-not [Environment]::Is64BitProcess) { throw 'Start this check from 64-bit PowerShell.' }
    foreach ($systemPath in 'System32','SysWOW64') {
        $hostExe = Join-Path $env:SystemRoot "$systemPath\WindowsPowerShell\v1.0\powershell.exe"
        & $hostExe -NoProfile -NonInteractive -File $PSCommandPath -Package $packageDir -CheckProcess
        if ($LASTEXITCODE) { throw "$systemPath TSF smoke test failed" }
    }
    return
}
Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class KunKunTsfProbe {
    [DllImport("kernel32", CharSet=CharSet.Unicode, ExactSpelling=true, SetLastError=true)]
    private static extern IntPtr LoadLibraryExW(string name, IntPtr reserved, uint flags);
    [DllImport("kernel32", ExactSpelling=true, CharSet=CharSet.Ansi, SetLastError=true)]
    private static extern IntPtr GetProcAddress(IntPtr module, string exportName);
    [DllImport("kernel32", ExactSpelling=true)]
    private static extern bool FreeLibrary(IntPtr module);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int GetClass(ref Guid clsid, ref Guid iid, out IntPtr instance);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int Create(IntPtr factory, IntPtr outer, ref Guid iid, out IntPtr obj);

    public static void Check(string path) {
        IntPtr module = LoadLibraryExW(path, IntPtr.Zero, 0x1100);
        if (module == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
        try {
            foreach (string entry in new string[] { "DllGetClassObject", "DllCanUnloadNow", "DllRegisterServer", "DllUnregisterServer" }) {
                if (GetProcAddress(module, entry) == IntPtr.Zero) throw new Exception("Missing COM export " + entry);
            }
            GetClass get = (GetClass)Marshal.GetDelegateForFunctionPointer(GetProcAddress(module, "DllGetClassObject"), typeof(GetClass));
            Guid iidFactory = new Guid("00000001-0000-0000-C000-000000000046");
            foreach (var sourceId in new string[] { "99C2EE30-5C57-45A2-9C63-FB54B34FD90A", "99C2DEB0-5C57-45A2-9C63-FB54B34FD90A" }) {
                Guid other = new Guid(sourceId);
                IntPtr rejected;
                if (get(ref other, ref iidFactory, out rejected) != unchecked((int)0x80040111) || rejected != IntPtr.Zero) throw new Exception("Foreign CLSID accepted");
            }
            Guid clsid = new Guid("D6101A78-E6DB-5FF2-AA53-D4018795C838");
            IntPtr factory;
            Marshal.ThrowExceptionForHR(get(ref clsid, ref iidFactory, out factory));
            try {
                IntPtr vtable = Marshal.ReadIntPtr(factory);
                Create create = (Create)Marshal.GetDelegateForFunctionPointer(Marshal.ReadIntPtr(vtable, IntPtr.Size * 3), typeof(Create));
                Guid tip = new Guid("AA80E7F7-2021-11D2-93E0-0060B067B86E");
                IntPtr service;
                Marshal.ThrowExceptionForHR(create(factory, IntPtr.Zero, ref tip, out service));
                if (service == IntPtr.Zero) throw new Exception("Missing ITfTextInputProcessor interface");
                Marshal.Release(service);
            } finally { Marshal.Release(factory); }
        } finally { FreeLibrary(module); }
    }
}
'@
$fileName = if ([Environment]::Is64BitProcess) { 'kunkun_tsf.dll' } else { 'kunkun_tsf_x86.dll' }
$path = (Resolve-Path -LiteralPath (Join-Path $packageDir $fileName)).Path
[KunKunTsfProbe]::Check($path)
Write-Output "PASS $fileName : COM exports, independent CLSID and ITfTextInputProcessor"

