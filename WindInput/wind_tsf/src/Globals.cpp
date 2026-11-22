#include "Globals.h"
#include <appmodel.h>
#include <sddl.h>  // ConvertSidToStringSidW（per-user 管道名）
#include <string>
#include <vector>

// 链接必要的库
// 注意：msctf.lib 在新版 SDK 中不需要直接链接
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "oleaut32.lib")
#pragma comment(lib, "uuid.lib")
#pragma comment(lib, "user32.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "advapi32.lib")  // OpenProcessToken / GetTokenInformation / ConvertSidToStringSidW

// 全局变量定义
HINSTANCE g_hInstance = nullptr;
LONG g_lServerLock = 0;

// 困困输入法的稳定系统标识。
// {D6101A78-E6DB-5FF2-AA53-D4018795C838}
const CLSID c_clsidTextService =
    { 0xd6101a78, 0xe6db, 0x5ff2, { 0xaa, 0x53, 0xd4, 0x01, 0x87, 0x95, 0xc8, 0x38 } };

// {22A5794A-3482-57B7-999A-28D9523E6D4B}
const GUID c_guidProfile =
    { 0x22a5794a, 0x3482, 0x57b7, { 0x99, 0x9a, 0x28, 0xd9, 0x52, 0x3e, 0x6d, 0x4b } };

// {44270A8E-2032-569A-9B80-16E524933488}
const GUID c_guidLangBarItemButton =
    { 0x44270a8e, 0x2032, 0x569a, { 0x9b, 0x80, 0x16, 0xe5, 0x24, 0x93, 0x34, 0x88 } };

// {DE2EC003-02BC-59F4-B8D9-9802149EFAE6}
const GUID c_guidDisplayAttributeInput =
    { 0xde2ec003, 0x02bc, 0x59f4, { 0xb8, 0xd9, 0x98, 0x02, 0x14, 0x9e, 0xfa, 0xe6 } };

// {59AB7260-53DD-5E70-AFF7-604F6A3AC337}
const GUID c_guidDisplayAttributeConverted =
    { 0x59ab7260, 0x53dd, 0x5e70, { 0xaf, 0xf7, 0x60, 0x4f, 0x6a, 0x3a, 0xc3, 0x37 } };

LONG DllAddRef()
{
    return InterlockedIncrement(&g_lServerLock);
}

LONG DllRelease()
{
    return InterlockedDecrement(&g_lServerLock);
}

namespace
{
    std::wstring _BaseNameFromPath(const std::wstring& path)
    {
        if (path.empty())
            return L"";

        size_t pos = path.find_last_of(L"\\/");
        if (pos == std::wstring::npos || pos + 1 >= path.length())
            return path;

        return path.substr(pos + 1);
    }

    BOOL _QueryProcessPath(HANDLE hProcess, std::wstring& path)
    {
        WCHAR buffer[MAX_PATH * 2] = {};
        DWORD size = ARRAYSIZE(buffer);
        if (!QueryFullProcessImageNameW(hProcess, 0, buffer, &size))
            return FALSE;

        path.assign(buffer, size);
        return TRUE;
    }

    void _QueryTokenMetadata(HANDLE hProcess, WindHostProcessInfo& info)
    {
        HANDLE hToken = nullptr;
        if (!OpenProcessToken(hProcess, TOKEN_QUERY, &hToken))
        {
            info.queryError = GetLastError();
            return;
        }

        DWORD isAppContainer = 0;
        DWORD returnLength = 0;
        if (GetTokenInformation(hToken, TokenIsAppContainer, &isAppContainer, sizeof(isAppContainer), &returnLength))
            info.isAppContainer = isAppContainer ? TRUE : FALSE;

        GetTokenInformation(hToken, TokenIntegrityLevel, nullptr, 0, &returnLength);
        if (returnLength > 0)
        {
            std::vector<BYTE> tokenBuffer(returnLength);
            if (GetTokenInformation(hToken, TokenIntegrityLevel, tokenBuffer.data(), returnLength, &returnLength))
            {
                auto* til = reinterpret_cast<TOKEN_MANDATORY_LABEL*>(tokenBuffer.data());
                DWORD subAuthCount = *GetSidSubAuthorityCount(til->Label.Sid);
                if (subAuthCount > 0)
                    info.integrityRid = *GetSidSubAuthority(til->Label.Sid, subAuthCount - 1);
            }
        }

        UINT32 packageLen = PACKAGE_FAMILY_NAME_MAX_LENGTH;
        WCHAR packageName[PACKAGE_FAMILY_NAME_MAX_LENGTH] = {};
        LONG packageResult = GetPackageFamilyName(hProcess, &packageLen, packageName);
        if (packageResult == ERROR_SUCCESS)
            info.packageFamilyName.assign(packageName, packageLen);

        CloseHandle(hToken);
    }

    BOOL _QueryProcessInfo(HANDLE hProcess, DWORD processId, DWORD threadId, HWND hwnd, WindHostProcessInfo* info)
    {
        if (info == nullptr)
            return FALSE;

        *info = WindHostProcessInfo{};
        info->processId = processId;
        info->threadId = threadId;
        info->hwnd = hwnd;

        if (hwnd != nullptr)
        {
            WCHAR className[256] = {};
            int classLen = GetClassNameW(hwnd, className, ARRAYSIZE(className));
            if (classLen > 0)
                info->windowClass.assign(className, classLen);

            // 只取长度、不留原文（标题是用户数据，见 WindHostProcessInfo::windowTitleLen）。
            // InternalGetWindowText 不发 WM_GETTEXT：GetWindowTextW 对本进程窗口会发，宿主 UI
            // 线程卡住时日志采集就跟着卡住。
            WCHAR title[256] = {};
            int titleLen = InternalGetWindowText(hwnd, title, ARRAYSIZE(title));
            info->windowTitleLen = titleLen > 0 ? titleLen : 0;
            SecureZeroMemory(title, sizeof(title));
        }

        if (hProcess == nullptr)
        {
            info->queryError = ERROR_INVALID_HANDLE;
            return FALSE;
        }

        if (!_QueryProcessPath(hProcess, info->processPath))
            info->queryError = GetLastError();

        info->processName = _BaseNameFromPath(info->processPath);
        _QueryTokenMetadata(hProcess, *info);
        return info->queryError == ERROR_SUCCESS || !info->processPath.empty();
    }
}

namespace {
    // 当前进程令牌的用户 SID 字符串（`S-1-5-...`）；失败返回空串。
    // 与 Rust wind-bridge::pipe_scope::current_user_sid 用同一 OS API，产出同一字符串。
    std::wstring _CurrentUserSidString()
    {
        HANDLE hToken = nullptr;
        if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &hToken))
            return L"";

        std::wstring sid;
        DWORD len = 0;
        GetTokenInformation(hToken, TokenUser, nullptr, 0, &len);  // 先探长度
        if (len > 0)
        {
            std::vector<BYTE> buf(len);
            if (GetTokenInformation(hToken, TokenUser, buf.data(), len, &len))
            {
                auto* tu = reinterpret_cast<TOKEN_USER*>(buf.data());
                LPWSTR s = nullptr;
                if (ConvertSidToStringSidW(tu->User.Sid, &s))
                {
                    sid.assign(s);
                    LocalFree(s);
                }
            }
        }
        CloseHandle(hToken);
        return sid;
    }

    // `\\.\pipe\{base}` 再按需追加 `_{SID}`（扁平后缀，不加路径段）。
    std::wstring _BuildPipeName(const wchar_t* base)
    {
        std::wstring name = L"\\\\.\\pipe\\";
        name += base;
        std::wstring sid = _CurrentUserSidString();
        if (!sid.empty())
        {
            name += L"_";
            name += sid;
        }
        return name;
    }
}

// 主/推送管道名：进程内惰性求值一次（含 SID），静态局部存活至进程退出，
// 故返回的 c_str() 全程有效。管道名必须和服务中的主通道及推送通道一致。
const wchar_t* WindPipeName()
{
    static const std::wstring name = _BuildPipeName(L"kunkun_ime");
    return name.c_str();
}

const wchar_t* WindPushPipeName()
{
    static const std::wstring name = _BuildPipeName(L"kunkun_ime_push");
    return name.c_str();
}

BOOL WindQueryCurrentProcessInfo(WindHostProcessInfo* info)
{
    return _QueryProcessInfo(GetCurrentProcess(), GetCurrentProcessId(), GetCurrentThreadId(), nullptr, info);
}

BOOL WindQueryWindowProcessInfo(HWND hwnd, WindHostProcessInfo* info)
{
    if (hwnd == nullptr || info == nullptr)
        return FALSE;

    DWORD processId = 0;
    DWORD threadId = GetWindowThreadProcessId(hwnd, &processId);

    HANDLE hProcess = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, processId);
    BOOL ok = _QueryProcessInfo(hProcess, processId, threadId, hwnd, info);
    if (hProcess != nullptr)
        CloseHandle(hProcess);

    if (!ok && info->queryError == ERROR_SUCCESS)
        info->queryError = GetLastError();

    return ok;
}

void WindLogHostProcessInfo(int level, const wchar_t* prefix, const WindHostProcessInfo& info)
{
    WindLog::OutputFmt(
        level,
        L"%ls pid=%lu tid=%lu hwnd=0x%p appContainer=%d integrityRid=0x%04lX class=%ls title_len=%d exe=%ls package=%ls queryError=%lu",
        prefix ? prefix : L"host",
        info.processId,
        info.threadId,
        info.hwnd,
        info.isAppContainer ? 1 : 0,
        info.integrityRid,
        info.windowClass.empty() ? L"-" : info.windowClass.c_str(),
        info.windowTitleLen,
        info.processPath.empty() ? (info.processName.empty() ? L"-" : info.processName.c_str()) : info.processPath.c_str(),
        info.packageFamilyName.empty() ? L"-" : info.packageFamilyName.c_str(),
        info.queryError
    );
}

void WindLogCurrentProcessInfo(int level, const wchar_t* prefix)
{
    if (!WindLog::IsEnabled(level))
        return;

    WindHostProcessInfo info;
    if (WindQueryCurrentProcessInfo(&info))
    {
        WindLogHostProcessInfo(level, prefix, info);
        return;
    }

    WindLog::OutputFmt(level, L"%ls query_failed queryError=%lu",
                       prefix ? prefix : L"current", info.queryError);
}

void WindLogForegroundProcessInfo(int level, const wchar_t* prefix)
{
    // 前置闸门：下面的进程信息采集（OpenProcess + 令牌查询 + 映像路径 + 窗口标题长度）
    // 代价远高于一条日志，而本函数的调用点在按键路径上。
    // 缺此闸门时，即便日志级别低于 level、这套查询也照跑不误——日志宏只能延后
    // 「格式化」，挡不住实参位置的函数调用，那是 C++ 求值顺序保证要先做的事。
    if (!WindLog::IsEnabled(level))
        return;

    WindHostProcessInfo info;
    HWND hwndForeground = GetForegroundWindow();
    if (WindQueryWindowProcessInfo(hwndForeground, &info))
    {
        WindLogHostProcessInfo(level, prefix, info);
        return;
    }

    WindLog::OutputFmt(level, L"%ls hwnd=0x%p queryError=%lu", prefix ? prefix : L"foreground", hwndForeground, info.queryError);
}
