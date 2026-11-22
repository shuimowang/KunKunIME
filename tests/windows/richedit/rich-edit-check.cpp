#define UNICODE
#define _UNICODE
#include <windows.h>
#include <msctf.h>
#include <richedit.h>
#include <wrl/client.h>
#include <cstdio>
#include <filesystem>
#include <string>
#include <vector>

using Microsoft::WRL::ComPtr;
namespace fs = std::filesystem;

static void check(HRESULT hr, const char *call) {
    if (FAILED(hr)) {
        std::fprintf(stderr, "%s: hr=0x%08lx\n", call, static_cast<unsigned long>(hr));
        throw 1;
    }
}
static void need(bool ok, const char *message) {
    if (!ok) { std::fprintf(stderr, "%s (win32=%lu)\n", message, GetLastError()); throw 1; }
}
static std::string utf8(const std::wstring &value) {
    if (value.empty()) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr);
    std::string result(n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), result.data(), n, nullptr, nullptr);
    return result;
}
static void pump(DWORD ms) {
    const auto end = GetTickCount64() + ms;
    do {
        MSG msg;
        while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
        if (GetTickCount64() < end) MsgWaitForMultipleObjects(0, nullptr, FALSE, 12, QS_ALLINPUT);
    } while (GetTickCount64() < end);
}
static std::wstring editorText(HWND h) {
    std::wstring s(GetWindowTextLengthW(h) + 1, L'\0');
    s.resize(GetWindowTextW(h, s.data(), static_cast<int>(s.size())));
    return s;
}
static void checkServer(const fs::path &expected) {
    HANDLE h = CreateFileW(LR"(\\.\pipe\kunkun_ime_ctrl)", GENERIC_READ|GENERIC_WRITE,
                           0, nullptr, OPEN_EXISTING, 0, nullptr);
    need(h != INVALID_HANDLE_VALUE, "Start KunKunIME.exe --service first");
    ULONG pid = 0;
    BOOL found = GetNamedPipeServerProcessId(h, &pid);
    CloseHandle(h);
    need(found, "GetNamedPipeServerProcessId");
    HANDLE proc = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    need(proc != nullptr, "OpenProcess");
    wchar_t path[32768]; DWORD len = ARRAYSIZE(path);
    found = QueryFullProcessImageNameW(proc, 0, path, &len); CloseHandle(proc);
    need(found && fs::equivalent(expected, path), "Control pipe is owned by another package");
    std::printf("service_pid=%lu package=%s\n", pid, utf8(path).c_str());
}
static std::vector<std::wstring> candidateList(ITfUIElementMgr *ui) {
    std::vector<std::wstring> words;
    ComPtr<IEnumTfUIElements> all; check(ui->EnumUIElements(&all), "EnumUIElements");
    ComPtr<ITfUIElement> item; ULONG got;
    while (all->Next(1, &item, &got) == S_OK) {
        ComPtr<ITfCandidateListUIElement> list;
        if (SUCCEEDED(item.As(&list))) {
            UINT n=0; list->GetCount(&n);
            for (UINT i=0; i<n && i<9; i++) {
                BSTR word = nullptr;
                if (SUCCEEDED(list->GetString(i, &word)) && word) {
                    words.emplace_back(word, SysStringLen(word)); SysFreeString(word);
                }
            }
        }
        item.Reset();
    }
    return words;
}
static bool key(ITfKeystrokeMgr *keys, UINT vk) {
    const LPARAM down = 1 | (static_cast<LPARAM>(MapVirtualKeyW(vk, MAPVK_VK_TO_VSC)) << 16);
    BOOL handled=FALSE;
    check(keys->TestKeyDown(vk,down,&handled), "TestKeyDown");
    if (!handled) return false;
    check(keys->KeyDown(vk,down,&handled), "KeyDown");
    const LPARAM up = down | static_cast<LPARAM>(0xc0000000u);
    BOOL release=FALSE; check(keys->TestKeyUp(vk,up,&release), "TestKeyUp");
    if (release) check(keys->KeyUp(vk,up,&release), "KeyUp");
    pump(40);
    return !!handled;
}
static HWND realWindow = nullptr, realEditor = nullptr, previousWindow = nullptr;
static void guardedKey(ITfKeystrokeMgr *keys, UINT vk, bool optional=false) {
    if (!realWindow) {
        const bool handled=key(keys,vk); if (!optional && !handled) {
            std::fprintf(stderr,"private key declined: vk=%u\n",vk);
            throw 1;
        }
        return;
    }
    need(GetForegroundWindow()==realWindow && GetFocus()==realEditor, "Foreground has moved; stopped typing");
    INPUT batch[2]{};
    for (int i=0;i<2;++i) {
        batch[i].type=INPUT_KEYBOARD;
        batch[i].ki.wVk=static_cast<WORD>(vk);
        batch[i].ki.wScan=static_cast<WORD>(MapVirtualKeyW(vk,MAPVK_VK_TO_VSC));
    }
    batch[1].ki.dwFlags=KEYEVENTF_KEYUP;
    need(SendInput(2,batch,sizeof(INPUT))==2, "SendInput failed");
    pump(62);
}
struct RestoreWindow {
    ~RestoreWindow() {
        if (realWindow && previousWindow && IsWindow(previousWindow) && GetForegroundWindow()==realWindow)
            SetForegroundWindow(previousWindow);
    }
};
static void checkText(ITfKeystrokeMgr *keys, ITfUIElementMgr *ui, HWND editor,
                      const wchar_t *code, const std::wstring &previous, const std::wstring &expected) {
    need(editorText(editor)==previous && GetFocus()==editor, "Unexpected starting editor text or focus");
    for (const wchar_t *ch=code; *ch; ++ch) {
        need(GetFocus()==editor, "private editor lost focus");
        guardedKey(keys,static_cast<UINT>(*ch-'a'+'A'));
    }
    pump(180);
    const auto words = candidateList(ui);
    std::printf("code=%s TSF_candidate_count=%zu",utf8(code).c_str(),words.size());
    for(auto &w:words)std::printf(" [%s]",utf8(w).c_str());std::puts("");
    guardedKey(keys,VK_SPACE);
    pump(180);
    // Dismiss the next-word suggestion composition before inspecting the persisted text.
    guardedKey(keys,VK_ESCAPE,true);pump(150);
    const auto live=editorText(editor);
    std::printf("actual_RichEdit_text=%s\n",utf8(live).c_str());
    need(live==previous+expected,"Committed RichEdit document does not match expected text");
}
int wmain(int argc,wchar_t **argv) {
    if(argc!=3){std::puts("usage: rich-edit-check <KunKunIME.exe> local|direct|installed|installed-visible");return 2;}
    setvbuf(stdout,nullptr,_IONBF,0);
    try {
        checkServer(fs::absolute(argv[1]));
        const std::wstring mode=argv[2];
        const bool visible=mode==L"installed-visible";
        need(mode==L"local" || mode==L"installed" || visible || mode==L"direct","Unknown test mode");
        RestoreWindow restore;
        previousWindow=visible ? GetForegroundWindow() : nullptr;
        std::wstring desktopName=L"KunKunIME-QA-"+std::to_wstring(GetCurrentProcessId());
        if (!visible) {
            HDESK desk=CreateDesktopW(desktopName.c_str(),nullptr,nullptr,0,GENERIC_ALL,nullptr);
            need(desk && SetThreadDesktop(desk), "Could not create isolated non-input desktop");
        }
        check(OleInitialize(nullptr),"OleInitialize");
        GUID cls{},profile{};
        check(CLSIDFromString(L"{D6101A78-E6DB-5FF2-AA53-D4018795C838}",&cls),"CLSID");
        check(CLSIDFromString(L"{22A5794A-3482-57B7-999A-28D9523E6D4B}",&profile),"profile");
        ComPtr<ITfThreadMgr> mgr;
        check(CoCreateInstance(CLSID_TF_ThreadMgr,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&mgr)),"ThreadMgr");
        ComPtr<ITfThreadMgrEx> ex;check(mgr.As(&ex),"ITfThreadMgrEx");
        TfClientId host=TF_CLIENTID_NULL;
        check(ex->ActivateEx(&host,mode==L"direct"?TF_TMAE_NOACTIVATETIP:0),"ActivateEx thread");
        need(LoadLibraryW(L"msftedit.dll")!=nullptr,"load Msftedit");
        HWND wnd=CreateWindowW(L"STATIC",L"KunKunIME input verification",WS_OVERLAPPEDWINDOW,
                               20,20,600,200,nullptr,nullptr,GetModuleHandleW(nullptr),nullptr);
        HWND ed=CreateWindowExW(0,MSFTEDIT_CLASS,L"",WS_CHILD|WS_VISIBLE|WS_TABSTOP|ES_MULTILINE,
                                8,8,560,130,wnd,nullptr,GetModuleHandleW(nullptr),nullptr);
        need(wnd && ed,"Create RichEdit window");
        SendMessageW(ed,EM_SETEDITSTYLE,SES_USECTF,SES_USECTF);
        const auto current=GetCurrentThreadId();
        const auto other=visible && previousWindow ? GetWindowThreadProcessId(previousWindow,nullptr):0;
        const bool attach=other && other!=current && AttachThreadInput(current,other,TRUE);
        if(visible){realWindow=wnd; realEditor=ed;}
        ShowWindow(wnd,SW_SHOW);SetForegroundWindow(wnd);SetActiveWindow(wnd);SetFocus(ed);
        if(attach)AttachThreadInput(current,other,FALSE);
        pump(320);
        if(visible)need(GetForegroundWindow()==wnd && GetFocus()==ed,"Could not focus designated RichEdit; no input sent");
        ComPtr<ITfDocumentMgr> focused;check(mgr->GetFocus(&focused),"GetFocus");
        std::printf("editor_focus=%d tsf_document=%d mode=%s\n",GetFocus()==ed,!!focused,utf8(mode).c_str());
        need(GetFocus()==ed && focused,"RichEdit did not create a focused TSF document");
        ComPtr<ITfInputProcessorProfiles> profiles;
        check(CoCreateInstance(CLSID_TF_InputProcessorProfiles,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&profiles)),"Profiles");
        ComPtr<ITfInputProcessorProfileMgr> pm;check(profiles.As(&pm),"ProfileMgr");
        ComPtr<ITfTextInputProcessor> tip;
        if(mode==L"direct") {
            check(CoCreateInstance(cls,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&tip)),"reg-free TIP class");
            ComPtr<ITfClientId> clients;check(mgr.As(&clients),"ITfClientId");
            TfClientId tipId;check(clients->GetClientId(cls,&tipId),"GetClientId");
            check(tip->Activate(mgr.Get(),tipId),"TIP Activate");
        } else {
            if(mode==L"local") {
                const wchar_t label[]=L"困困输入法";const auto dll=fs::absolute(argv[1]).parent_path()/L"kunkun_tsf.dll";
                check(pm->RegisterProfile(cls,0x0804,profile,label,ARRAYSIZE(label)-1,dll.c_str(),
                                          static_cast<ULONG>(dll.native().size()),0,nullptr,0,TRUE,TF_RP_LOCALPROCESS),
                      "RegisterProfile(LOCALPROCESS)");
            }
            check(pm->ActivateProfile(TF_PROFILETYPE_INPUTPROCESSOR,0x0804,cls,profile,nullptr,TF_IPPMF_FORPROCESS),
                  "ActivateProfile(FORPROCESS)");
            pump(400);
            TF_INPUTPROCESSORPROFILE active{};
            check(pm->GetActiveProfile(GUID_TFCAT_TIP_KEYBOARD,&active),"GetActiveProfile");
            need(active.clsid==cls && active.guidProfile==profile,"Private profile not active");
        }
        pump(550);
        HMODULE loaded=GetModuleHandleW(L"kunkun_tsf.dll");
        wchar_t modulePath[32768];need(loaded && GetModuleFileNameW(loaded,modulePath,ARRAYSIZE(modulePath)), "KunKunIME text service DLL was not loaded");
        if(mode==L"installed" || visible) {
            wchar_t installed[32768]; DWORD bytes=sizeof(installed);
            const auto reg = RegGetValueW(HKEY_LOCAL_MACHINE,
                LR"(SOFTWARE\Classes\CLSID\{D6101A78-E6DB-5FF2-AA53-D4018795C838}\InprocServer32)",
                nullptr, RRF_RT_REG_SZ | RRF_SUBKEY_WOW6464KEY, nullptr, installed, &bytes);
            need(reg==ERROR_SUCCESS && fs::equivalent(installed,modulePath),"Loaded DLL does not match installed COM server");
        }
        std::printf("loaded_TSF=%s\n",utf8(modulePath).c_str());
        ComPtr<ITfKeystrokeMgr> keys;ComPtr<ITfUIElementMgr> ui;
        check(mgr.As(&keys),"KeystrokeMgr");check(mgr.As(&ui),"UIElementMgr");
        GUID foregroundClass{};
        HRESULT foregroundResult=keys->GetForeground(&foregroundClass);
        std::printf("TSF_foreground_class_result=%08lx matches=%d OS_foreground_is_test=%d\n",
                    long(foregroundResult),foregroundClass==cls,GetForegroundWindow()==wnd);
        const std::pair<const wchar_t*,const wchar_t*> inputs[]={
            {L"beijingdaxue", L"北京大学"}, {L"shurufa",L"输入法"},
            {L"yongpinyinxieyipianwenzhang",L"用拼音写一篇文章"}, {L"bailixuance",L"百里玄策"}
        };
        std::wstring expected;
        for(auto &input:inputs) {
            checkText(keys.Get(),ui.Get(),ed,input.first,expected,input.second);
            expected += input.second;
        }
        if(tip)check(tip->Deactivate(),"TIP Deactivate");
        if(visible && GetForegroundWindow()==wnd && IsWindow(previousWindow))SetForegroundWindow(previousWindow);
        DestroyWindow(wnd);pump(80);check(mgr->Deactivate(),"ThreadMgr Deactivate");
        std::puts("PASS: four full-Pinyin commits delivered through the new TSF DLL to RichEdit");
        return 0;
    } catch (...) {return 1;}
}

