//! wind_setting — 困困输入法原生设置应用（基于 windui）。
//!
//! 通过 JSON-RPC over named pipe 与核心服务通信，不改动 core 接口。
//! 无边框窗口 + 自定义标题栏，7 Tab 清单驱动（manifest 分组），运行时明暗换肤。

mod accent;
mod add_word_window;
mod appearance_preview;
mod auto_encode;
mod by_layout;
mod capabilities;
mod cli;
mod compat_window;
mod completion_rules;
mod config;
mod correction_ui;
mod custom_hide;
mod dialogs;
mod dict_window;
mod dota2_compat;
mod file_assoc;
#[cfg(windows)]
pub mod windows_import_associations {
    //! File formats and Shell commands shared with the Windows uninstaller.
    pub use crate::file_assoc::{ALL, icon_value_for_exe, notify_shell_assoc_changed};
    pub use crate::protocol::command_for_exe;
}
mod file_pick;
mod hide_marker;
mod hide_menu;
#[cfg(test)]
mod hide_menu_e2e;
#[cfg(test)]
mod hide_menu_structure;
mod icons;
mod key_conflict;
mod log_tools;
mod logger;
mod look;
mod look_window;
mod manifest;
mod memstat;
mod mix_fields;
mod mix_members;
mod mode;
mod net_task;
mod open_external;
mod pages;
mod protocol;
#[cfg(windows)]
mod register_ime;
mod rpc;
mod schema_url_import;
mod search;
#[allow(dead_code)]
mod setting_config;
mod shell;
mod shortcuts;
mod state;
mod subnav;
mod text_file;
mod theme_def;
#[allow(dead_code)]
mod theme_editor_server;
mod theme_follow;
mod theme_gallery;
#[allow(dead_code)]
mod theme_import;
mod tsf_log;
#[allow(dead_code)]
mod update;
mod user_dir;
mod user_hide;
mod weight;
mod widgets;
mod wizard;
#[cfg(target_os = "linux")]
mod xdg_assoc;

/// 计数分配器：仅 `--features memstat` 时接管全局分配，用于把内存占用拆到具体构建阶段。
#[cfg(feature = "memstat")]
#[global_allocator]
static ALLOC: memstat::Counting = memstat::Counting;

use std::cell::RefCell;
use std::rc::Rc;

use windui::core::EventCtx;
use windui::prelude::*;

/// 设置和词库共用同一窗口，容纳词库的数据域、分类和列表三栏。
pub(crate) const MAIN_W: i32 = 990;
pub(crate) const MAIN_H: i32 = 640;
pub(crate) const MAIN_MIN_W: i32 = 760;
pub(crate) const MAIN_MIN_H: i32 = 440;

/// 应用兼容窗尺寸：三栏（分类 184 + 进程列表 270 + 配置信息），配置信息一行要放下
/// 标题、说明与控件，所以比词库窗宽一圈。
pub(crate) const COMPAT_W: i32 = 1040;
pub(crate) const COMPAT_H: i32 = 680;
pub(crate) const COMPAT_MIN_W: i32 = 900;
pub(crate) const COMPAT_MIN_H: i32 = 480;

/// 主窗无论打开哪个页面，都以同一个键参与平台窗口单例仲裁。
pub(crate) const MAIN_SINGLE_KEY: &str = "main";
const MAIN_TITLE: &str = "困困输入法 · 设置与词库";

/// 主窗的窗口级快捷键处理器。绑定表与分派都在 `shortcuts`——加一条快捷键在那里加一行即可。
///
/// 首个主窗（`App::on_shortcut`）与重建的主窗（`Window::on_shortcut`，见 [`open_main_window`]）
/// 各挂一份：windui 只把 `App::on_shortcut` 交给首窗，重建窗不挂就整张快捷键表失灵。
fn main_shortcut_handler(
    app: Rc<RefCell<state::AppState>>,
) -> impl FnMut(&mut windui::event::ShortcutCtx, windui::event::KeyEvent) -> bool + 'static {
    move |ctx, ev| {
        let out = shortcuts::dispatch(&ev, &app.borrow());
        if out.focus_main_input {
            ctx.focus_main_input();
        }
        if out.request_close {
            ctx.request_close();
        }
        // 提交词库页的加词对话框。先把 `DictManagerState` clone 出来（它是一组信号 +
        // `Rc`，clone 很便宜，且不回指 `AppState`），提交期间不持有 `AppState` 的
        // 借用——那期间要发 RPC、写信号、触发重排，让一个 `RefCell` 借用横跨这些
        // 是没必要冒的险。
        if let Some(kind) = out.submit_dict {
            let mgr = app.borrow().dict_mgr.clone();
            match kind {
                shortcuts::DictSubmit::Word => mgr.submit_word(),
                shortcuts::DictSubmit::Shadow => mgr.submit_shadow(),
            }
        }
        out.handled
    }
}

fn settings_window_content(app: &Rc<RefCell<state::AppState>>) -> Element {
    let conn = app.borrow().conn;
    // 保持设置树的响应式更新运行，等读取完成再开放表单和键盘焦点。
    let form = shell::build(app).visible_when(move || conn.get() != state::ConnState::Loading);
    let loading = Element::col()
        .fill()
        .bg_role(Role::Bg)
        .child(shell::build_titlebar("设置与词库"))
        .child(Element::divider())
        .child(
            Element::stack().fill().weight(1.0).child(
                Element::label("正在读取数据…")
                    .fg_role(Role::TextMuted)
                    .align(Align::Center),
            ),
        )
        .visible_when(move || conn.get() == state::ConnState::Loading);
    let mut root = Element::stack().fill().child(form).child(loading);
    if update::update_check_supported() {
        let current = app.clone();
        let watcher = on_about_entry(&app.borrow(), move || {
            let state = current.borrow();
            if !state.manifest.feature_hidden("update_check") {
                state.begin_update_check();
            }
        });
        root = root.child(watcher);
    }
    root
}

/// Watch the visible page, including direct launches and switching back from the dictionary.
fn on_about_entry(state: &state::AppState, mut enter: impl FnMut() + 'static) -> Element {
    let about = cli::page_to_index("about").expect("registered About page");
    let (tab, dictionary, connection, closed) = (
        state.tab,
        state.dictionary_visible,
        state.conn,
        state.main_window_closed,
    );
    let visible = move || {
        tab.get() == about && !dictionary.get() && !closed.get()
            && connection.get() != state::ConnState::Loading
    };
    let mut check = move || {
        if visible() {
            enter();
        }
    };
    check();
    pages::dict::reactive::reactive_effect(move || u64::from(visible()), check)
}

fn autosave_settings(app: &Rc<RefCell<state::AppState>>) {
    if let Ok(mut st) = app.try_borrow_mut() {
        st.autosave_tick();
    }
}

/// 标题栏、系统关闭和快捷键共用此路径。写入失败时留在设置页重试。
fn finish_settings_close(st: &mut state::AppState) -> bool {
    if !st.flush_autosave() {
        st.return_to_settings();
        return false;
    }
    st.on_main_window_closed();
    true
}

pub(crate) fn open_main_window(ctx: &mut EventCtx, app: &Rc<RefCell<state::AppState>>) {
    // 建窗前必然已注入（见 main 里 `set_theme_handle`）。真取不到就只能放弃重建：
    // 没有句柄，这个窗口的换肤（明暗、主题色）就与其余窗口脱钩。
    if app.borrow().theme_handle.borrow().is_none() {
        tracing::error!("主窗重建失败：换肤句柄缺失");
        return;
    }
    let st = app.clone();
    let guard = app.clone();
    let autosave = app.clone();
    let tick = app.clone();
    app.borrow().main_window_closed.set(false);
    let window = Window::new(MAIN_TITLE, MAIN_W, MAIN_H);
    #[cfg(windows)]
    let window = {
        let registration = app.borrow().dict_mgr.registration.clone();
        window.on_window_activated(move |_, active| {
            if active {
                registration.refresh();
            }
        })
    };
    ctx.open_window(
        window.single(MAIN_SINGLE_KEY)
            .frameless(true)
            .centered(true)
            .min_size(MAIN_MIN_W, MAIN_MIN_H)
            // 同 `main` 里那份：App 级定时器挂在首个主窗上、随它关掉了，重建的要自己挂。
            .on_interval(pages::memory::TICK, move |_ctx| {
                pages::memory::on_tick(&tick)
            })
            // 同 `main` 里的 `App::on_shortcut`：那份只归首个主窗，重建的要自己挂。
            .on_shortcut(main_shortcut_handler(app.clone()))
            .on_interval(state::AUTOSAVE_INTERVAL, move |_| {
                autosave_settings(&autosave)
            })
            .on_close_request(move |ctx| {
                let Ok(mut st) = guard.try_borrow_mut() else {
                    return false;
                };
                if st.update_phase.get() == update::UpdatePhase::Installing {
                    ctx.toast("正在安装更新，请等待完成后再关闭");
                    return false;
                }
                finish_settings_close(&mut st)
            })
            .content(move || settings_window_content(&st)),
    );
}

/// 深链切换主窗中的内容。词库页保留后台的设置位置。
fn route_target(st: &state::AppState, idx: usize) {
    if Some(idx) == cli::page_to_index("dict") {
        st.show_dictionary();
    } else {
        st.return_to_settings();
        st.tab.set(idx);
    }
}

/// 同步拉取数据并 apply；连接不存在时 fast-fail 立即返回。重连复用此入口。
///
/// 设置操作低频，故直接在调用线程同步 fetch（无后台线程）；
/// 若重连后仍失败，`apply_loaded` 会再次把 `conn_failed` 置 true，对话框保持。
pub fn main_reconnect(app: &Rc<RefCell<state::AppState>>) {
    let data = state::LoadedState::fetch();
    let connected = data.connected;
    app.borrow_mut().apply_loaded(data);
    // 启动时 core 没起来的用户，重连成功这一刻才是首启向导该弹的时候。
    wizard::maybe_auto_open(app, connected);
}

/// 单实例 app_id（含变体后缀，dev/release 互不干扰）。主窗口与加词小窗是两个独立单例，
/// 各占一个 id —— 加词小窗开着时点「设置」，两者互不顶替。
///
/// `claim_instance` 与 `App::single_instance` 必须用同一个 id，故收拢在此。
fn single_instance_app_id(add_word: bool) -> String {
    let suffix = mode::pipe_suffix();
    if add_word {
        format!("kunkun_settings_addword{suffix}")
    } else {
        format!("kunkun_settings{suffix}")
    }
}

pub fn run() {
    let args: Vec<String> = std::env::args().collect();

    // 单实例闸门：设置程序已开着时，本进程的使命就只是把 argv（深链页、协议 URL）递给
    // 首实例，然后死掉。故仲裁要赶在任何启动动作之前 —— windui 在 App::run 里也会仲裁，
    // 但那时这个注定要退场的进程已经轮转过日志、发了七八个 RPC、订阅了 core 事件通道，
    // theme_editor.auto_start 开着的话还会去抢端口。这些副作用全落在首实例头上。
    let add_word = cli::parse_add_word(&args);
    if windui::claim_instance(&single_instance_app_id(add_word.is_some()))
        == windui::InstanceRole::Handoff
    {
        return;
    }

    // 采样日志在单例闸门**之后**才清空：二次实例（切页深链）也会走到 main，
    // 放在闸门前会把首实例的采样记录一次次抹掉。
    memstat::reset_log();
    memstat::mark("[进程起点]");

    logger::init();
    memstat::mark("[日志就绪]");
    // 协议与 .wpkg 关联的自愈各有两道门：便携版不碰宿主机注册表，用户手动取消过
    // 注册的也不再写回去（两条关联各记各的意愿，取消其一不影响另一条）。
    // 单读一次配置而不等 AppState 建好 —— 自愈要赶在窗口出现前完成，此刻 AppState
    // 还没开始拉 core 数据。判定本身是纯函数，见 protocol::should_self_heal_on_startup。
    {
        let sc = setting_config::load().config;
        let portable = mode::is_portable();
        if protocol::should_self_heal_on_startup(portable, sc.protocol.self_heal) {
            protocol::self_heal_registration();
        }
    }

    // 快捷加词小窗：独立单例、紧凑窗，复用词库加词对话框；不落入常规设置界面路径。
    if let Some(params) = add_word {
        add_word_window::run(params);
        return;
    }

    // 快速初始化：仅读系统主题（~86µs），不阻塞任何 RPC，UI 立即可显示。
    let app_state = Rc::new(RefCell::new(state::AppState::empty()));
    memstat::mark("[AppState 建好]");
    {
        let st = app_state.borrow();
        if args.iter().any(|a| a == "--light") {
            st.dark.set(false);
        } else if args.iter().any(|a| a == "--dark") {
            st.dark.set(true);
        }
        // 初始页：--page <id> / --page=<id> / 快捷 --<id> / --tab <n>。
        if let Some(idx) = cli::parse_target(&args) {
            route_target(&st, idx);
        }
        // 词库页深链（--schema/--type）：此刻方案列表尚未就绪，故只挂起，
        // 待 apply_loaded 注入方案后再定位（见 AppState::route_dict_target）。
        if let Some(t) = cli::parse_dict_target(&args) {
            st.route_dict_target(t);
        }
        // 冷启动协议 URL：解析并路由（切相关页 + 占位提示）。
        if let Some(raw) = cli::parse_protocol(&args) {
            drop(st);
            route_protocol(&raw, &app_state);
        }
    }
    if app_state.borrow().setting_config.theme_editor.auto_start {
        app_state.borrow().start_theme_editor_server(true);
    }
    if let Some(w) = app_state.borrow().setting_config_warning.clone() {
        app_state.borrow().feedback.set(Some((ToastKind::Info, w)));
    }
    let start_dark = app_state.borrow().dark.get();
    // 跟随输入法主题色时，建窗前先取一次色：否则窗口先按默认蓝亮出来、数据到了再变色，
    // 开窗就闪一下。限时取，取不到先用默认色（见 `prefetch_accent`）。
    app_state.borrow().prefetch_accent();

    // 渲染：默认 `Renderer::Auto`（GPU 优先，远程会话 / 无可用 GPU 时由 windui 自动回退
    // 软渲染，不 panic）。命令行 --soft / --no-accel 强制软渲染。
    //
    // 显式写 Auto 而非省略：windui 的默认值是 `Software`（其 GPU 路径的验证仍在补齐），
    // 而本应用一直跑在 GPU 加速上——省略参数会静默降级成软渲染。
    let soft_render = args.iter().any(|a| a == "--soft" || a == "--no-accel");
    let renderer = if soft_render {
        Renderer::Software
    } else {
        Renderer::Auto
    };
    tracing::info!(
        "渲染模式: {}",
        if soft_render {
            "软件 (tiny-skia)"
        } else {
            "GPU 优先（不可用时自动回退软渲染）"
        }
    );

    let compat_as_main = cli::parse_compat_request(&args);
    app_state.borrow().main_window_closed.set(compat_as_main);
    let (title, (win_w, win_h), (win_min_w, win_min_h)) = if compat_as_main {
        (
            compat_window::TITLE,
            (COMPAT_W, COMPAT_H),
            (COMPAT_MIN_W, COMPAT_MIN_H),
        )
    } else {
        (MAIN_TITLE, (MAIN_W, MAIN_H), (MAIN_MIN_W, MAIN_MIN_H))
    };
    memstat::mark("[App::new 前]");
    let app = App::new(title, win_w, win_h)
        .single_window(if compat_as_main {
            compat_window::SINGLE_KEY
        } else {
            MAIN_SINGLE_KEY
        })
        .frameless()
        .centered()
        .renderer(renderer)
        .min_size(win_min_w, win_min_h)
        .theme(app_state.borrow().ui_theme());
    // 窗口级快捷键。绑定表与分派都在 `shortcuts`——加一条快捷键在那里加一行即可。
    //
    // ⚠ Escape 这条**必须**有人管：TextInput 不处理 Escape，未消费的 Escape 会被宿主
    // 当成「关窗兜底」——搜索到一半按 Esc 就把整个设置窗关掉了。`on_shortcut` 排在
    // Tab 焦点导航与 Escape 关窗**之前**，正是为覆盖这类默认行为；而浮层没开时
    // `dispatch` 会返回「未处理」，关窗兜底照常生效。
    let mut app = app.on_shortcut(main_shortcut_handler(app_state.clone()));
    let th = app.theme_handle();
    // 存一份供重建主窗用：`App::theme_handle()` 只在建窗前拿得到，而重建发生在运行期。
    *app_state.borrow().theme_handle.borrow_mut() = Some(th.clone());
    // 记下建窗用的那份（明暗, 强调色），之后同值就不再整窗换肤。
    app_state.borrow().apply_ui_theme();

    // 导入意图（`--page import` 深链 / `.wpkg` 文件关联双击）统一经此通道到 UI 线程：
    // 读剪贴板要 `EventCtx`，只有 channel 回调拿得到；channel 靠出帧排空，冷启动在
    // 首帧执行，正好也保证了 UI 树已就绪。冷启动与二次唤起同一条路，行为不会分叉。
    let import_action_state = app_state.clone();
    let import_tx = app.channel::<ImportAction>(move |ctx, act| {
        run_import_action(ctx, act, &import_action_state);
    });

    // 冷启动的导入意图：解析在此，执行在首帧（见上）。
    if cli::parse_import_request(&args) {
        let _ = import_tx.send(ImportAction::ClipboardDialog);
    } else if let Some(p) = cli::parse_import_file(&args) {
        let _ = import_tx.send(ImportAction::File(p));
    }

    // 在窗口事件循环里处理外部启动。这样即使首窗已关闭、进程只剩兼容窗，
    // 仍可用 EventCtx 把请求送到主窗；重复启动复用同一个窗口键。
    let launch_state = app_state.clone();
    let launch_tx = app.channel::<Vec<String>>(move |ctx, argv| {
        route_second_launch(ctx, &argv, &launch_state);
    });
    let mut app = app.single_instance(single_instance_app_id(false), move |argv| {
        let _ = launch_tx.send(argv);
    });

    // 注册后台→UI 通道：数据就绪后在 UI 线程回调，更新 Signal 刷新界面。
    let app_state_cb = app_state.clone();
    let tx = app.channel::<state::LoadedState>(move |_ctx, data| {
        let connected = data.connected;
        app_state_cb.borrow_mut().apply_loaded(data);
        memstat::mark("[apply_loaded 后]");
        // 首启自动弹设置向导：数据就绪后才能按当前配置预填。
        wizard::maybe_auto_open(&app_state_cb, connected);
    });

    // 后台线程并行执行全部 RPC（fast-fail，管道不存在时几乎立即返回）。
    std::thread::spawn(move || {
        let data = state::LoadedState::fetch();
        if tx.send(data).is_err() {
            tracing::warn!("窗口已关闭，后台数据发送取消");
        }
    });

    // core 事件订阅：控制通道是请求-响应的，问不到 core 自发的状态变更（用户用快捷键
    // 或托盘切了方案等）。这里订阅单向事件管道，让界面跟着变——否则「当前方案」这类
    // 显示只在打开页面那一刻正确，之后会变成看起来权威的错误信息。
    // 监听线程内阻塞读 + 断线重连，故与升级通道一样经 channel 回 UI 线程写 Signal。
    let events_state = app_state.clone();
    let events_tx = app.channel::<rpc::CoreEvent>(move |ctx, ev| {
        events_state.borrow_mut().on_core_event(ctx, &ev);
    });
    std::thread::spawn(move || {
        rpc::listen_events(|ev| {
            // 发送失败 = 窗口已关闭，监听线程随进程退出即可。
            let _ = events_tx.send(ev);
        });
    });

    // 升级通道：下载线程不能直接写 Signal（Signal 是线程局部 arena 句柄，非 Send），
    // 故所有升级进度/结果都经此 channel 回到 UI 线程。channel 只能在 App 构建期注册，
    // 因此这里建好后把 Sender 存进 AppState 供各按钮取用。
    let update_state = app_state.clone();
    let update_tx = app.channel::<update::UpdateMsg>(move |_ctx, msg| {
        update_state.borrow().on_update_msg(msg);
    });
    app_state.borrow().set_update_tx(update_tx);

    // 后台网络任务通道：HTTP 下载在工作线程跑，续体经此回 UI 线程执行。续体要借
    // `AppState`，所以先把 `NetTasks` 的 Rc 克隆出来、放掉 `AppState` 的借用再调。
    let net = app_state.borrow().net.clone();
    let net_cb = net.clone();
    let net_tx = app.channel::<net_task::NetDone>(move |_ctx, done| net_cb.on_done(done));
    net.set_sink(move |done| {
        let _ = net_tx.send(done);
    });

    // 内存诊断通道：`system.memoryStats` 在后台线程取（core 不在线时要等管道超时），
    // 结果经此回 UI 线程写摘要 / 对话框（见 `pages::memory`）。
    let memory_state = app_state.clone();
    let memory_tx = app.channel::<pages::memory::MemoryReply>(move |_ctx, r| {
        memory_state.borrow().memory.on_reply(r);
    });
    app_state.borrow().memory.set_tx(memory_tx);

    // 词库统计通道：`dict.systemStats` 要扫整份系统词库（拼音大词库一秒上下），在后台线程
    // 取，结果经此回 UI 线程写对话框（见 `pages::dict::stats_dialog`）。
    let stats_state = app_state.clone();
    let stats_tx = app.channel::<pages::dict::stats_dialog::StatsReply>(move |_ctx, r| {
        let stats = stats_state.borrow().dict_mgr.stats.clone();
        stats.on_reply(r);
    });
    app_state.borrow().dict_mgr.stats.set_tx(stats_tx);

    #[cfg(windows)]
    {
        let dict = app_state.borrow().dict_mgr.clone();
        let registration = dict.registration.clone();
        let registration_tx = app.channel::<register_ime::Reply>(move |ctx, reply| {
            match dict.registration.on_reply(reply) {
                Some(Ok(message)) => ctx.toast_ok(message),
                Some(Err(message)) => ctx.toast_err(message),
                None => {}
            }
        });
        registration.set_tx(registration_tx);
        registration.refresh();
        app = app.on_window_activated(move |_, active| {
            if active {
                registration.refresh();
            }
        });
    }

    // 日志打包通道：压缩在后台线程做，完成后在这里弹提示、在文件管理器里选中压缩包。
    let pack_state = app_state.clone();
    let log_pack_tx = app.channel::<log_tools::PackResult>(move |ctx, r| {
        pack_state.borrow().log_pack_busy.set(false);
        log_tools::on_pack_done(ctx, r);
    });
    *app_state.borrow().log_pack_tx.borrow_mut() = Some(log_pack_tx);

    // Windows 的 ZIP 发行版在进入「关于」时检查，并支持手动重试。
    // 其他平台沿用原安装器的静默检查门控。
    // 无更新或失败都不打扰用户，只有确实有新版本且未被「跳过此版本」屏蔽才弹对话框。
    if !cfg!(windows) {
        let now = chrono::Local::now().timestamp();
        let cfg = app_state.borrow().setting_config.update.clone();
        let forced = update::force_check_requested(&args);
        // 强制检查仍受 network_consent 门控 —— 调试后门不该越过联网同意
        let gate = if !update::update_check_supported() {
            // 没有元数据可查的平台不发请求。门控放在这里而不是 should_auto_check 里边 ——
            // 那是个纯函数，三重门控的语义与平台无关，掺进平台判断会让它的单测在别的平台
            // 上集体失效。
            false
        } else if forced {
            cfg.network_consent
        } else if custom_hide::update_check_hidden(custom_hide::installed()) {
            // 定制版藏了「检查更新」：开窗也不自动查、不弹升级框（同 `feature:wizard`）。
            false
        } else {
            update::should_auto_check(cfg.network_consent, cfg.auto_check, cfg.last_check_at, now)
        };
        if forced && !cfg.network_consent {
            tracing::warn!("请求了强制检查更新，但用户尚未同意联网，已跳过");
        }
        if gate {
            if forced {
                // 刻意不写 last_check_at：调试用的强制检查不该消耗掉真实的节流窗口，
                // 否则跑一次调试就会让接下来 24h 的自动检查失效，反而更难验证。
                tracing::warn!("强制检查更新（调试入口）：已绕过 24h 节流与自动检查开关");
            } else {
                // 发起前就记时间：失败也算"查过"，免得每次开设置都重试
                app_state.borrow_mut().mark_update_checked(now);
            }
            app_state.borrow().spawn_silent_update_check();
        }
    }

    let ui = if compat_as_main {
        // 兼容窗不建设置外壳，静态色控件的亮暗双态要自己初始化。
        crate::widgets::init_dark_sig(app_state.borrow().dark);
        // 界面细节信号同理：不注册的话卡片拿到孤立的缺省信号，不跟随 `[ui.look]`。
        crate::widgets::init_look(app_state.borrow().look);
        compat_window::build_content(app_state.clone())
    } else {
        settings_window_content(&app_state)
    };

    // 自绘 ×、系统关闭、Ctrl+W 和无前置弹层时的 Escape 共用关闭请求。
    if compat_as_main {
        let compat_for_activate = app_state.borrow().compat_mgr.clone();
        app = app.on_window_activated(move |_ctx, active| {
            // 同 `compat_window::open`：回到本窗就刷新。
            if active {
                compat_for_activate.request_refresh();
            }
        });
    }
    // 诊断信息对话框的「每 2 秒自动刷新」。定时器挂在主窗上、随主窗生灭（重建的主窗在
    // `open_main_window` 里另挂一份）。对话框关着或没勾时回调什么都不做——不写信号就不出帧
    // （见 `App::on_interval` 的文档），代价只是每 2 秒一次平台定时器唤醒。
    if !compat_as_main {
        let autosave = app_state.clone();
        app = app.on_interval(state::AUTOSAVE_INTERVAL, move |_| {
            autosave_settings(&autosave)
        });
        let tick_state = app_state.clone();
        app = app.on_interval(pages::memory::TICK, move |_ctx| {
            pages::memory::on_tick(&tick_state)
        });
    }
    // 主题风格跟随系统时，系统明暗一变候选窗就换了一套颜色：主题页的缩略图按已生效的配置
    // 判断要不要重取（画廊按 core 当前明暗出图），外观页「效果预览」按草稿自己判断。
    let theme_state = app_state.clone();
    app = app.on_system_theme_changed(move |_ctx, _dark| {
        let Ok(st) = theme_state.try_borrow() else {
            return;
        };
        st.system_theme_flips.update(|n| *n += 1);
        if appearance_preview::follows_system(st.current_config.get_str("ui.theme.style")) {
            st.theme_gallery.invalidate();
        }
    });
    let close_state = app_state.clone();
    app = app.on_close_request(move |ctx| {
        let Ok(mut st) = close_state.try_borrow_mut() else {
            return false;
        };
        // 正在装 .deb：进程一退，apt/dpkg 继承的输出管道就断了，写入即 SIGPIPE，
        // 包会停在半配置状态。等装完再关。
        if st.update_phase.get() == update::UpdatePhase::Installing {
            ctx.toast("正在安装更新，请等待完成后再关闭");
            return false;
        }
        // 应用兼容窗的改动即时落盘，同样没有「未保存改动」。
        if compat_as_main {
            return true;
        }
        finish_settings_close(&mut st)
    });

    // 内存诊断：运行期每 2s 打一个净堆采样点，用于分辨「切页涨的内存」到底在 Rust 堆
    // 上还是在图形栈里。开着它会持续唤醒消息循环，与 d2d 的"空闲零 CPU"设计相冲，
    // 所以只在 memstat 构建里挂。
    #[cfg(feature = "memstat")]
    let app = app.on_interval(std::time::Duration::from_secs(2), |_| {
        memstat::mark("[运行期]")
    });

    memstat::mark("[run 之前]");
    app.bg(theme_def::theme_for(start_dark).palette.bg)
        // `--screenshot <path>` 离屏出图（配合 WIND_RPC_MOCK=1 可无 core 核对 UI）。
        .screenshot_from_args()
        .content(ui)
        .run();
}

/// UI 就绪后才能执行的导入动作（解析在 argv 侧，执行在首帧的 channel 回调里）。
enum ImportAction {
    /// `--page import` 深链：切高级页 + 弹「导入配置」+ 读一次剪贴板。
    ClipboardDialog,
    /// `.wpkg` 文件关联双击：走统一分派器进方案包导入确认。
    File(std::path::PathBuf),
}

/// 已运行时，从托盘、Quicker、文件关联等入口重新打开应用。
fn route_second_launch(ctx: &mut EventCtx, argv: &[String], app: &Rc<RefCell<state::AppState>>) {
    if cli::parse_compat_request(argv) {
        compat_window::open(ctx, app);
        return;
    }

    if let Some(raw) = cli::parse_protocol(argv) {
        route_protocol(&raw, app);
    } else if cli::parse_import_request(argv) {
        run_import_action(ctx, ImportAction::ClipboardDialog, app);
    } else if let Some(p) = cli::parse_import_file(argv) {
        run_import_action(ctx, ImportAction::File(p), app);
    } else if let Some(idx) = cli::parse_target(argv) {
        let st = app.borrow();
        if let Some(target) = cli::parse_dict_target(argv) {
            st.route_dict_target(target);
        }
        route_target(&st, idx);
    } else {
        app.borrow().return_to_settings();
    }
    open_main_window(ctx, app);
}

/// 导入动作统一落点（冷启动与二次唤起同路）。
///
/// 文件分支不切页：方案包导入确认对话框挂在根上（全窗模态），与当前页无关；
/// 剪贴板分支切到高级页 —— 「导入配置」对话框关掉后用户应落在它的入口所在页。
fn run_import_action(
    ctx: &mut windui::core::EventCtx,
    act: ImportAction,
    state: &Rc<RefCell<state::AppState>>,
) {
    let st = state.borrow();
    match act {
        ImportAction::ClipboardDialog => {
            // `--page import`（命令直通车 `setting.open("import")` 也走这里）：定制版藏了
            // `feature:config_import` 就不弹导入框，窗口照常打开。
            if st.manifest.feature_hidden("config_import") {
                tracing::info!("定制版已隐藏配置导入，忽略 --page import");
                return;
            }
            if let Some(idx) = cli::page_to_index("advanced") {
                route_target(&st, idx);
            }
            st.config_import.open();
            st.config_import.read_clipboard(ctx);
        }
        ImportAction::File(p) => {
            let ov = pages::config_import::ThemePkgConfirmSignals::of(&st);
            pages::config_import::dispatch_import_path(&st.config_import, &st.schema_mgr, ov, p);
        }
    }
}

/// 协议 URL 路由：解析 → 切到相关页；主题导入暂存到待确认状态并弹出导入对话框；
/// 方案包（schema）仅接受官方域，下载到临时文件后复用 schema_manager 既有的导入
/// 确认对话框；其余 kind 提示暂不支持。反馈走 feedback（toast）——底栏只剩状态点，
/// 没有常驻文字位。
fn route_protocol(raw: &str, state: &Rc<RefCell<state::AppState>>) {
    match protocol::parse_protocol_url(raw) {
        Ok(req) => {
            let st = state.borrow();
            // 切到**这类导入的落点页**，让用户看得见接下来要确认的东西：主题导入的对话框
            // 属于主题页、方案包导入的确认框属于方案页。不认识的 kind 不切页——只弹一句
            // "暂不支持"，把用户留在原处。
            //
            let target_page = match req.kind.as_str() {
                "theme" => cli::page_to_index("theme"),
                "schema" => cli::page_to_index("schema"),
                _ => None,
            };
            if let Some(i) = target_page {
                route_target(&st, i);
            }
            tracing::info!(
                "协议导入请求: kind={} url={} name={}",
                req.kind,
                req.url,
                req.name
            );
            if req.kind == "theme" && st.theme_pkg_confirm_busy() {
                // 主题包确认 / 覆盖确认开着时不接新链接：新导入框会盖在确认框上，用户
                // 分不清手里点的是哪一个（见 `AppState::open_downloaded_theme_package`）。
                st.notify_err("上一个主题包还在等待确认，请先处理完再导入新的");
            } else if req.kind == "theme" {
                st.set_pending_theme_import(req.url, req.name);
                st.theme_import_dialog_visible.set(true);
                st.feedback.set(Some((
                    ToastKind::Info,
                    "收到主题导入请求，请确认来源后导入".to_string(),
                )));
            } else if req.kind == "schema" {
                st.clear_pending_theme_import();
                st.hide_protocol_theme_dialog();
                // 白名单逐跳校验（域名不对不发请求，重定向出官方域即拒）。下载与预览在后台
                // 线程跑，完成后弹确认框。临时文件的生命周期跟着句柄走：确认对话框是异步的，
                // 句柄交给对话框，确认 / 取消后随待导入项释放即删；预览失败时在后台线程里
                // drop 即删（见 schema_url_import）。
                state::AppState::start_schema_download(state, req.url);
            } else {
                st.clear_pending_theme_import();
                st.hide_protocol_theme_dialog();
                st.feedback.set(Some((
                    ToastKind::Info,
                    format!("暂不支持通过协议导入 {}", req.kind),
                )));
            }
        }
        Err(e) => {
            let st = state.borrow();
            st.clear_pending_theme_import();
            st.hide_protocol_theme_dialog();
            st.feedback
                .set(Some((ToastKind::Error, format!("协议解析失败：{e}"))));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn windows_update_runs_on_entry_to_visible_about_page() {
        use std::cell::Cell;
        use windui::{core::Tree, geometry::Size};

        let state = state::AppState::empty();
        let about = cli::page_to_index("about").unwrap();
        let calls = Rc::new(Cell::new(0));
        state.tab.set(about);
        let observed = calls.clone();
        let effect = on_about_entry(&state, move || observed.set(observed.get() + 1));
        let mut tree = Tree::new();
        tree.root = Some(effect.build(&mut tree));
        let mut frame = || tree.layout_root(Size::new(10, 10), &mut windui::text::NullTextEngine);
        frame();
        assert_eq!(calls.get(), 0);
        state.conn.set(state::ConnState::Ready);
        frame();
        frame();
        assert_eq!(calls.get(), 1);
        state.dictionary_visible.set(true);
        frame();
        state.tab.set(0);
        state.dictionary_visible.set(false);
        frame();
        assert_eq!(calls.get(), 1);
        state.tab.set(about);
        frame();
        assert_eq!(calls.get(), 2);
        let observed = calls.clone();
        let _reopened = on_about_entry(&state, move || observed.set(observed.get() + 1));
        assert_eq!(calls.get(), 3);
    }

    /// ★ 重建出来的主窗必须自己挂快捷键：`App::on_shortcut` 只归首个主窗，子窗（含关掉后
    /// 重开的设置窗）只认自己的 `Window::on_shortcut`。漏挂的后果是关窗再从托盘 / 二次实例
    /// 打开后 Ctrl+F、Ctrl+W、Esc 全失灵。
    #[test]
    fn rebuilt_main_window_keeps_window_shortcuts() {
        let app = Rc::new(RefCell::new(state::AppState::empty()));
        *app.borrow().theme_handle.borrow_mut() = Some(ThemeHandle::detached(Theme::default()));
        let res = windui::testing::run_with_ctx(|ctx| open_main_window(ctx, &app));
        assert_eq!(res.open_windows.len(), 1, "应当开出一个主窗");
        assert!(
            res.open_windows[0].shortcut.is_some(),
            "重建的主窗没挂 on_shortcut"
        );
    }

    #[test]
    fn autosave_closing_settings_flushes_the_focused_number_before_the_next_frame_or_timer() {
        use windui::core::Tree;
        use windui::event::{Key, KeyEvent};
        use windui::geometry::Size;
        use windui::text::NullTextEngine;

        let _lock = rpc::ENV_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        unsafe { std::env::set_var("WIND_RPC_MOCK", "1") };
        struct Mock;
        impl Drop for Mock {
            fn drop(&mut self) {
                unsafe { std::env::remove_var("WIND_RPC_MOCK") };
            }
        }
        let _mock = Mock;
        let app = Rc::new(RefCell::new(state::AppState::empty()));
        let key = "schema.pinyin.sentence_count";
        let mut tree = Tree::new();
        {
            let mut st = app.borrow_mut();
            st.apply_loaded(state::LoadedState::fetch());
            *st.theme_handle.borrow_mut() = Some(ThemeHandle::detached(Theme::default()));
            let field = manifest::field(&st, key).build(&mut tree);
            tree.root = Some(field);
            tree.layout_root(Size::new(700, 80), &mut NullTextEngine);
            st.seed_autosave_baseline();
        }

        let focus = tree
            .focusable_order()
            .into_iter()
            .find(|&id| tree.get(id).unwrap().widget.accepts_text())
            .expect("the bound setting must contain an editable number field");
        tree.set_focused(Some(focus), None);
        rpc::take_mock_calls();
        for (key, ctrl) in [(Key::Other(0x41), true), (Key::Char('7'), false)] {
            tree.dispatch_key(
                KeyEvent {
                    key,
                    ctrl,
                    pressed: true,
                    shift: false,
                    alt: false,
                    meta: false,
                },
                Some(focus),
            );
        }
        let mut window = windui::testing::run_with_ctx(|ctx| open_main_window(ctx, &app))
            .open_windows
            .pop()
            .unwrap();
        let mut close = window.close_handler.take().unwrap();
        let mut allowed = false;
        windui::testing::run_with_ctx(|ctx| allowed = close(ctx));
        assert!(allowed);
        let calls = rpc::take_mock_calls();
        assert!(
            calls
                .iter()
                .any(|(method, params)| method == "config.setItems"
                    && params["items"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .any(|i| i["key"] == key && i["value"] == 7)),
            "the very last number typed must be on disk before allowing the close; calls: {calls:?}"
        );
    }

    /// `--page import`（含命令直通车 `setting.open("import")`）在 `feature:config_import`
    /// 下不弹导入框、不切页；没藏时照常弹。
    #[test]
    fn page_import_deep_link_follows_config_import_hide() {
        let run = |hide: &[&str]| {
            let app = Rc::new(RefCell::new(state::AppState::empty()));
            let (h, _) = wind_config::SettingsHide::parse(hide);
            app.borrow_mut().manifest = custom_hide::settings_manifest(&h, false);
            windui::testing::run_with_ctx(|ctx| {
                run_import_action(ctx, ImportAction::ClipboardDialog, &app)
            });
            let st = app.borrow();
            (st.config_import.visible.get(), st.tab.get())
        };
        let advanced = cli::page_to_index("advanced").unwrap();
        assert_eq!(run(&[]), (true, advanced), "前提：没藏时弹框并切到高级页");
        let (visible, tab) = run(&["feature:config_import"]);
        assert!(!visible, "藏了仍弹导入框");
        assert_ne!(tab, advanced, "藏了仍切到高级页");
    }

    #[test]
    fn route_protocol_stores_theme_import_request() {
        let state = Rc::new(RefCell::new(state::AppState::empty()));

        route_protocol(
            "kunkunime://import/theme?url=https%3A%2F%2Fexample.com%2Fdark.toml&name=Dark",
            &state,
        );

        let st = state.borrow();
        assert_eq!(st.tab.get(), crate::cli::page_to_index("theme").unwrap());
        assert_eq!(
            st.pending_theme_import_url.get(),
            "https://example.com/dark.toml"
        );
        assert_eq!(st.pending_theme_import_name.get(), "Dark");
        assert!(st.theme_import_dialog_visible.get());
        assert_eq!(
            st.feedback.get(),
            Some((
                ToastKind::Info,
                "收到主题导入请求，请确认来源后导入".to_string()
            ))
        );
    }

    /// 主题包确认框开着时来了新的主题链接：不弹导入框盖住它，只提示。
    #[test]
    fn route_protocol_theme_waits_for_open_package_confirm() {
        let state = Rc::new(RefCell::new(state::AppState::empty()));
        state.borrow().theme_pkg_confirm_visible.set(true);

        route_protocol(
            "kunkunime://import/theme?url=https%3A%2F%2Fwindinput.com%2Fb.wtheme",
            &state,
        );

        let st = state.borrow();
        assert!(!st.theme_import_dialog_visible.get());
        assert_eq!(st.pending_theme_import_url.get(), "");
        assert!(matches!(st.feedback.get(), Some((ToastKind::Error, _))));
    }

    #[test]
    fn route_protocol_reports_unsupported_non_theme_import() {
        let state = Rc::new(RefCell::new(state::AppState::empty()));

        route_protocol(
            "kunkunime://import/theme?url=https%3A%2F%2Fexample.com%2Fdark.toml&name=Dark",
            &state,
        );
        route_protocol(
            "kunkunime://import/dict?url=https%3A%2F%2Fexample.com%2Fdict.zip&name=Dict",
            &state,
        );

        let st = state.borrow();
        // 不支持的 kind **不切页**：停在上一次主题请求切到的主题页，而不是被送去词库
        // 那一格永不显示的占位（切过去就是一屏空白）。
        assert_eq!(st.tab.get(), crate::cli::page_to_index("theme").unwrap());
        assert_eq!(st.pending_theme_import_url.get(), "");
        assert_eq!(st.pending_theme_import_name.get(), "");
        assert!(!st.theme_import_dialog_visible.get());
        assert_eq!(
            st.feedback.get(),
            Some((ToastKind::Info, "暂不支持通过协议导入 dict".to_string()))
        );
    }

    /// schema kind 已接通，但只认官方域：非白名单域在**发出任何网络请求之前**
    /// 被拒并 toast（本测试因此不需要网络）。
    #[test]
    fn route_protocol_schema_rejects_non_official_domain() {
        let state = Rc::new(RefCell::new(state::AppState::empty()));

        route_protocol(
            "kunkunime://import/schema?url=https%3A%2F%2Fexample.com%2Fpkg.zip&name=Pkg",
            &state,
        );

        let st = state.borrow();
        // 方案包导入的确认框属于**方案页**，不是词库（旧代码 "theme=3、其余=4" 的两档
        // 粗分让它落在了 4，那是词库，与方案包毫无关系）。
        assert_eq!(st.tab.get(), crate::cli::page_to_index("schema").unwrap());
        assert!(!st.theme_import_dialog_visible.get());
        let (kind, msg) = st.feedback.get().expect("应有反馈");
        assert_eq!(kind, ToastKind::Error);
        assert!(msg.contains("官方域名"), "{msg}");
    }

    #[test]
    fn route_protocol_reports_parse_error() {
        let state = Rc::new(RefCell::new(state::AppState::empty()));

        route_protocol(
            "kunkunime://import/theme?url=https%3A%2F%2Fexample.com%2Fdark.toml&name=Dark",
            &state,
        );
        route_protocol("kunkunime://import/theme", &state);

        let st = state.borrow();
        let (kind, msg) = st.feedback.get().expect("应有反馈");
        assert_eq!(kind, ToastKind::Error);
        assert!(msg.starts_with("协议解析失败："));
        assert_eq!(st.pending_theme_import_url.get(), "");
        assert_eq!(st.pending_theme_import_name.get(), "");
        assert!(!st.theme_import_dialog_visible.get());
    }

    #[test]
    fn unified_window_dictionary_deep_link_preserves_settings_location_across_repeated_launches() {
        let st = state::AppState::empty();
        let keys = cli::page_to_index("keys").unwrap();
        let dict = cli::page_to_index("dict").unwrap();
        route_target(&st, keys);
        route_target(&st, dict);
        route_target(&st, dict);
        assert!(st.dictionary_visible.get());
        assert_eq!(st.tab.get(), keys);
        st.return_to_settings();
        assert!(!st.dictionary_visible.get());
        assert_eq!(st.tab.get(), keys);
        route_target(&st, dict);
        let theme = cli::page_to_index("theme").unwrap();
        route_target(&st, theme);
        assert!(!st.dictionary_visible.get());
        assert_eq!(st.tab.get(), theme);
    }

    #[test]
    fn unified_window_secondary_launches_target_one_window_and_honor_dictionary_shortcuts() {
        let app = Rc::new(RefCell::new(state::AppState::empty()));
        *app.borrow().theme_handle.borrow_mut() = Some(ThemeHandle::detached(Theme::default()));
        let run = |args: &[&str]| {
            let argv = args.iter().map(|v| v.to_string()).collect::<Vec<_>>();
            let effects =
                windui::testing::run_with_ctx(|ctx| route_second_launch(ctx, &argv, &app));
            assert_eq!(effects.open_windows.len(), 1);
            effects.open_windows[0].single.clone().unwrap()
        };
        assert_eq!(run(&["exe", "--page", "input"]), MAIN_SINGLE_KEY);
        assert_eq!(
            run(&["exe", "--dict", "--schema", "pinyin", "--type", "user-dict"]),
            MAIN_SINGLE_KEY
        );
        assert!(app.borrow().dictionary_visible.get());
        assert!(app.borrow().awaiting_dict_target());
        assert_eq!(run(&["exe", "--page=dict"]), MAIN_SINGLE_KEY);
        assert_eq!(run(&["exe"]), MAIN_SINGLE_KEY);
        assert!(!app.borrow().dictionary_visible.get());
        assert_eq!(app.borrow().tab.get(), cli::page_to_index("input").unwrap());
        assert_eq!(run(&["exe", "--page", "compat"]), compat_window::SINGLE_KEY);
    }

    #[test]
    fn unified_window_repeated_dictionary_launch_keeps_the_active_word_editor_target() {
        let app = Rc::new(RefCell::new(state::AppState::empty()));
        *app.borrow().theme_handle.borrow_mut() = Some(ThemeHandle::detached(Theme::default()));
        let mgr = app.borrow().dict_mgr.clone();
        mgr.edit_visible.set(true);
        mgr.edit_text.set("尚未提交的人名".into());
        let before = (mgr.domain.get(), mgr.sub_tab.get());
        let argv = vec![
            "exe".into(),
            "--dict".into(),
            "--schema=pinyin".into(),
            "--type=user-dict".into(),
        ];
        let result = windui::testing::run_with_ctx(|ctx| route_second_launch(ctx, &argv, &app));
        assert_eq!(
            result.open_windows[0].single.as_deref(),
            Some(MAIN_SINGLE_KEY)
        );
        assert!(app.borrow().dictionary_visible.get());
        assert!(!app.borrow().awaiting_dict_target());
        assert!(mgr.edit_visible.get());
        assert_eq!(mgr.edit_text.get(), "尚未提交的人名");
        assert_eq!((mgr.domain.get(), mgr.sub_tab.get()), before);
    }

    /// 主窗关闭后停止诊断刷新，即使独立的兼容窗口继续运行。
    #[test]
    fn closing_main_window_stops_memory_auto_refresh() {
        let st = state::AppState::empty();
        st.memory.visible.set(true);
        st.memory.auto.set(true);

        st.on_main_window_closed();

        assert!(st.main_window_closed.get());
        assert!(!st.memory.visible.get());
        assert!(!st.memory.auto.get());
    }
}
