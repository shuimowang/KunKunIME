# UniFFI 0.32.0 workspace license

`LICENSE` is the unmodified MPL-2.0 text from Mozilla's UniFFI repository:

https://github.com/mozilla/uniffi-rs/blob/5c7b73906358e1a7acdc1bdc7bf5cd86fb27e44c/LICENSE

SHA-256: `1f256ecad192880510e84ad60474eab7589218784b9a50bc7ceee34c2b91f1d5`

The locked 0.32.0 archives for `uniffi`, `uniffi_bindgen`, `uniffi_core`,
`uniffi_internal_macros`, `uniffi_macros`, `uniffi_meta`, `uniffi_pipeline`,
and `uniffi_udl` declare MPL-2.0 but omit the workspace-root license file.
Their original `.crate` files were checked against `android/rust/Cargo.lock`;
each `.cargo_vcs_info.json` records this same commit and its matching crate
directory. The collector checks those identities before using this notice.

This exception applies only to these reviewed packages at version 0.32.0.
Other missing notices still fail collection. The source archive continues to
include the original, checksum-verified `.crate` files without modification.
