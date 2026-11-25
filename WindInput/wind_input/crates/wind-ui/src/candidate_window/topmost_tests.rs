//! Exercise actual Win32 z-order through CandidateWindow::show, including its cached-frame path.
//! No installed IME, physical keyboard input, or foreground activation is required.

use super::{CandidateItem, CandidateWindow, CandidateWindowConfig};
use crate::manager::{MenuAnchor, MenuItemSpec, MenuKind};
use crate::popup_menu::{PopupMenu, menu_visible};
use crate::window::LayeredWindow;
use windows::Win32::Foundation::{HWND, RECT};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::UI::Input::KeyboardAndMouse::{
    GetActiveWindow, GetFocus, SetActiveWindow, SetFocus,
};
use windows::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DestroyWindow, GW_HWNDNEXT, GetForegroundWindow, GetWindow, GetWindowRect,
    HMENU, HWND_TOPMOST, IsWindowVisible, SWP_NOACTIVATE, SWP_NOMOVE, SWP_NOSIZE, SWP_SHOWWINDOW,
    SetWindowPos, WINDOW_EX_STYLE, WS_CHILD, WS_EX_TOPMOST, WS_OVERLAPPEDWINDOW, WS_VISIBLE,
};
use windows::core::w;

struct InputHost(HWND);

impl InputHost {
    fn new() -> Self {
        unsafe {
            let module = GetModuleHandleW(None).expect("test process module");
            let host = Self(
                CreateWindowExW(
                    WS_EX_TOPMOST,
                    w!("STATIC"),
                    w!("KunKunIME topmost regression host"),
                    WS_OVERLAPPEDWINDOW,
                    20,
                    20,
                    640,
                    360,
                    HWND::default(),
                    HMENU::default(),
                    module,
                    None,
                )
                .expect("create topmost input host"),
            );
            let edit = CreateWindowExW(
                WINDOW_EX_STYLE(0),
                w!("EDIT"),
                w!(""),
                WS_CHILD | WS_VISIBLE,
                8,
                8,
                600,
                280,
                host.0,
                HMENU::default(),
                module,
                None,
            )
            .expect("create host edit control");
            host.raise();
            // Thread-local focus also works when a hosted runner has no foreground window.
            // These APIs can return a null previous handle on success; inspect the new focus.
            let _ = SetActiveWindow(host.0);
            let _ = SetFocus(edit);
            assert_eq!(GetFocus(), edit, "test edit control must hold keyboard focus");
            host
        }
    }

    fn raise(&self) {
        unsafe {
            SetWindowPos(
                self.0,
                HWND_TOPMOST,
                0,
                0,
                0,
                0,
                SWP_NOACTIVATE | SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW,
            )
            .expect("raise topmost host");
        }
    }
}

impl Drop for InputHost {
    fn drop(&mut self) {
        unsafe {
            let _ = DestroyWindow(self.0);
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
struct InputFocus {
    foreground: HWND,
    active: HWND,
    keyboard: HWND,
}

impl InputFocus {
    fn snapshot() -> Self {
        unsafe {
            Self {
                foreground: GetForegroundWindow(),
                active: GetActiveWindow(),
                keyboard: GetFocus(),
            }
        }
    }
}

fn bounds(hwnd: HWND) -> (i32, i32, i32, i32) {
    unsafe {
        let mut rect = RECT::default();
        GetWindowRect(hwnd, &mut rect).expect("read actual window rectangle");
        (rect.left, rect.top, rect.right, rect.bottom)
    }
}

fn is_above(upper: HWND, lower: HWND) -> bool {
    let mut next = unsafe { GetWindow(upper, GW_HWNDNEXT) }.ok();
    // A bounded traversal also fails safely if another process keeps rearranging windows.
    for _ in 0..4096 {
        let Some(hwnd) = next else { return false };
        if hwnd == lower {
            return true;
        }
        next = unsafe { GetWindow(hwnd, GW_HWNDNEXT) }.ok();
    }
    panic!("window z-order traversal did not terminate");
}

fn item(text: &str) -> CandidateItem {
    CandidateItem {
        text: text.to_owned(),
        text_styled: Default::default(),
        code: String::new(),
        label: String::new(),
        tooltip: Default::default(),
        comment: Default::default(),
        comment_above: Default::default(),
        no_index: false,
    }
}

struct OpenMenu(PopupMenu);

impl Drop for OpenMenu {
    fn drop(&mut self) {
        self.0.hide();
    }
}

#[test]
fn candidate_refresh_restores_topmost_without_stealing_focus_or_covering_popups() {
    let host = InputHost::new();
    let focus = InputFocus::snapshot();
    let (tx, _rx) = std::sync::mpsc::channel();
    let mut candidate = CandidateWindow::new(CandidateWindowConfig::default(), tx.clone())
        .expect("create real candidate window");
    candidate.set_fixed_position(Some((60, 90)));
    candidate.update("ni", 2, "", vec![item("你"), item("呢")], 0, -1, 1, 1);
    candidate.show();
    let hwnd = candidate.hwnd();
    assert!(unsafe { IsWindowVisible(hwnd) }.as_bool());
    assert!(is_above(hwnd, host.0), "first frame must be above the input host");
    assert_eq!(InputFocus::snapshot(), focus, "first frame must not activate the candidate");
    let original_bounds = bounds(hwnd);
    let original_pixels = candidate.window.buffer().to_vec();

    // Reproduce a pinned app raising itself after the candidate's previous frame. The next
    // frame is identical: neither the pixel nor geometry cache may suppress z-order repair.
    for _ in 0..2 {
        host.raise();
        assert!(is_above(host.0, hwnd), "precondition: pinned host covers candidate");
        candidate.show();
        assert_eq!(bounds(hwnd), original_bounds, "z-order repair must not move or resize");
        assert_eq!(candidate.window.buffer(), original_pixels.as_slice());
        assert!(is_above(hwnd, host.0), "unchanged candidate frame must recover z-order");
        assert_eq!(InputFocus::snapshot(), focus, "refresh must preserve input focus");
    }

    // Animation may change the candidate's geometry, but must not overtake a tooltip/popup.
    // Use an independent real layered window so Win32 owner ordering cannot mask a regression.
    let mut popup = LayeredWindow::create(None, 100, 40, "KunKunTopmostRegressionPopup")
        .expect("create popup above candidate");
    popup.buffer_mut().fill(255);
    popup.update().expect("paint popup");
    popup.show(70, 100);
    assert!(is_above(popup.hwnd(), hwnd));
    candidate.anim_repaint = true;
    candidate.set_fixed_position(Some((100, 130)));
    candidate.update(
        "ni",
        2,
        "",
        vec![item("A considerably wider candidate to exercise a resize during repaint")],
        0,
        -1,
        1,
        1,
    );
    candidate.show();
    candidate.anim_repaint = false;
    let animation_bounds = bounds(hwnd);
    assert_ne!(
        animation_bounds.2 - animation_bounds.0,
        original_bounds.2 - original_bounds.0,
        "precondition: animation repaint changed window width"
    );
    assert!(is_above(popup.hwnd(), hwnd), "animation resize must preserve popup z-order");
    assert_eq!(InputFocus::snapshot(), focus);

    // A pass-through skin layer may first appear, or reappear after a hidden frame, while
    // a popup is already open. Exercise the actual presenter without needing an animated theme.
    assert!(candidate.pass_window.is_none());
    let (pass_width, pass_height) = candidate.window.size();
    let mut decor_hwnd = None;
    for below in [false, false, true] {
        candidate.hide_pass_window();
        if let Some(decor) = &candidate.pass_window {
            assert!(!unsafe { IsWindowVisible(decor.hwnd()) }.as_bool());
        }
        candidate.pass_layer = Some((vec![255; (pass_width * pass_height * 4) as usize], below));
        candidate.present_pass_layer(
            animation_bounds.0,
            animation_bounds.1,
            pass_width,
            pass_height,
            true,
        );
        let decor = candidate.pass_window.as_ref().expect("create skin decor window").hwnd();
        if let Some(previous) = decor_hwnd {
            assert_eq!(decor, previous, "hidden decor window must be reused");
        }
        decor_hwnd = Some(decor);
        assert!(unsafe { IsWindowVisible(decor) }.as_bool());
        assert_eq!(bounds(decor), animation_bounds);
        assert!(is_above(popup.hwnd(), decor), "new or returning decor must stay below popup");
        if below {
            assert!(is_above(hwnd, decor), "below decor must stay below candidate");
        } else {
            assert!(is_above(decor, hwnd), "above decor must remain above candidate");
        }
        assert_eq!(InputFocus::snapshot(), focus);
    }
    candidate.hide_pass_window();
    popup.hide();

    // Open the actual candidate menu, then refresh both an identical frame and a moved frame.
    let mut menu = OpenMenu(PopupMenu::new(tx).expect("create real popup menu"));
    menu.0.show(
        vec![MenuItemSpec::leaf("Copy", MenuKind::Copy, true, false)],
        MenuAnchor::at_point(110, 140),
    );
    assert!(menu_visible());
    let menu_hwnd = menu.0.hwnd().expect("visible menu HWND");
    assert!(is_above(menu_hwnd, hwnd));
    candidate.show();
    assert!(is_above(menu_hwnd, hwnd), "unchanged refresh must not cover the open menu");
    candidate.set_fixed_position(Some((140, 170)));
    candidate.show();
    assert_ne!(bounds(hwnd), animation_bounds, "precondition: candidate moved with menu open");
    assert!(is_above(menu_hwnd, hwnd), "candidate movement must preserve menu z-order");
    assert_eq!(InputFocus::snapshot(), focus, "menu and candidate must preserve keyboard focus");
    menu.0.hide();
    assert!(!menu_visible());

    host.raise();
    assert!(is_above(host.0, hwnd));
    candidate.show();
    assert!(is_above(hwnd, host.0), "normal refresh resumes z-order recovery after menu closes");
    assert_eq!(InputFocus::snapshot(), focus);
}
