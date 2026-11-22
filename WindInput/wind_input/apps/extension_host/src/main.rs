//! 扩展宿主进程。由核心拉起，经 stdin/stdout 与核心通信；见 `lib.rs`。
// Windows 上不要控制台窗口：stdio 是核心建好的匿名管道，不依赖控制台。
#![cfg_attr(windows, windows_subsystem = "windows")]

fn main() {
    std::process::exit(wind_extension_host::run_from_args(std::env::args_os().skip(1)));
}
