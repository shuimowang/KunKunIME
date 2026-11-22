fn main() {
    // The unified application embeds its Windows executable resources in the service binary.
    println!("cargo:rustc-env=WIND_SETTING_VERSION=v{}", env!("CARGO_PKG_VERSION"));
}
