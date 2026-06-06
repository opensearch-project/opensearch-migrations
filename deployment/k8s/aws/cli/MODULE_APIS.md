# Ported module public APIs (import targets for remaining-tier ports)

All modules below are DONE, tested, and bash-3.2-verified. Import their pub funcs.
Arg order matters. core/std/term/ui/dashboard/timeline/state/log/common/version/
artifacts/discover/cfn_outputs/manifest/install are complete.

## core.ab — import { ... } from "./core.ab"
- term_detect(): Bool 
- term_interactive(): Bool 
- esc(): Text 
- eprint(s: Text): Null 
- eprintln(s: Text): Null 

## std.ab — import { ... } from "./std.ab"
- trim(s: Text): Text 
- trim_quotes(s: Text): Text 
- starts_with(prefix: Text, s: Text): Bool 
- ends_with(suffix: Text, s: Text): Bool 
- contains(needle: Text, s: Text): Bool 
- split_csv(s: Text): [Text] 
- join_by(sep: Text, parts: [Text]): Text 
- regex_capture(s: Text, pat: Text, group: Int): Text 
- regex_match(s: Text, pat: Text): Bool 
- count_lines_var(s: Text): Int 
- array_contains(needle: Text, arr: [Text]): Bool 
- dedupe(s: Text): [Text] 
- path_join(parts: [Text]): Text 
- is_macos(): Bool 
- is_linux(): Bool 
- optional_cmd(name: Text): Bool 
- require_var(name: Text): Bool 
- parse_flag_value(flag: Text, argv: [Text]): Text 
- parse_flag_rest(flag: Text, argv: [Text]): [Text] 
- retry_cmd(attempts: Int, delay: Int, cmd: Text): Int 

## term.ab — import { ... } from "./term.ab"
- term_winch(): Null 
- term_lines(): Int 
- term_columns(): Int 
- term_set_geometry(l: Int, c: Int): Null 
- term_hide_cursor(): Null 
- term_show_cursor(): Null 
- term_save_cursor(): Null 
- term_restore_cursor(): Null 
- term_clear_line(): Null 
- term_reset(): Null 
- term_install_reset_trap(): Null 
- term_install_winch_trap(): Null 
- term_wrap_off(): Null 
- term_wrap_on(): Null 
- term_link(url: Text, label: Text): Text 
- repeat_char(ch: Text, n: Int): Text 
- clip(s: Text, width: Int): Text 
- term_panel(title: Text, body: [Text]): Text 
- term_progress(cur: Int, total: Int, msg: Text): Text 
- term_set_title(title: Text): Null 
- term_spinner_frame(tick: Int): Text 

## ui.ab — import { ... } from "./ui.ab"
- ui_reset(): Text  
- ui_red(): Text    
- ui_green(): Text  
- ui_yellow(): Text 
- ui_blue(): Text   
- ui_cyan(): Text   
- ui_dim_c(): Text  
- ui_bold(): Text   
- ui_info(msg: Text): Null 
- ui_ok(msg: Text): Null 
- ui_warn(msg: Text): Null 
- ui_err(msg: Text): Null 
- ui_step(msg: Text): Null 
- ui_dim(msg: Text): Null 
- ui_banner(title: Text): Text 
- ui_prompt(prompt: Text, fallback: Text): Text 
- ui_confirm(prompt: Text, default_yn: Text): Bool 
- ui_select(prompt: Text, options: [Text]): Text 
- ui_table(header: Text, rows: [Text]): Text 
- ui_spinner_frame(tick: Int, msg: Text): Text 
- ui_spinner_clear(): Null 

## dashboard.ab — import { ... } from "./dashboard.ab"
- dash_init(header: Text): Null 
- dash_set_mode(mode: Text): Null 
- cfn_status_class(st: Text): Text 
- dash_classify(st: Text): Text 
- dash_upsert(key: Text, st: Text, reason: Text, ts: Text): Null 
- dash_clear(): Null 
- dash_count(): Int 
- dash_count_class(cls: Text): Int 
- dash_fmt_elapsed(started: Int): Text 
- dash_render(started: Int, header_override: Text): Text 
- dash_render_simple(started: Int): Text 
- dash_finish(started: Int): Text 

## timeline.ab — import { ... } from "./timeline.ab"
- timeline_phase_label(key: Text): Text 
- timeline_index_of(last: Text): Int 
- timeline_render(last: Text): Text 

## state.ab — import { ... } from "./state.ab"
- state_load(): Null 
- state_set(k: Text, v: Text): Null 
- state_get(k: Text, def: Text): Text 
- state_has(k: Text): Bool 
- state_unset(k: Text): Null 
- state_count(): Int 
- state_save(): Null 
- state_archive(): Null 
- state_resumable_step(): Text 

## log.ab — import { ... } from "./log.ab"
- fmt_log_line(ts: Text, lvl: Text, msg: Text): Text 
- fmt_stream_line(ts: Text, prefix: Text, line: Text): Text 
- fmt_stream_chrome(prefix: Text, line: Text): Text 
- log_init(): Null 
- log_announce_startup(): Null 
- log_announce_exit_line(): Null 
- log_announce(flag: Text): Null 
- log_info(msg: Text): Null  
- log_warn(msg: Text): Null  
- log_error(msg: Text): Null 
- log_debug(msg: Text): Null 
- log_announce_exit(rc: Int): Null 
- log_stream(prefix: Text, cmd: Text): Int 
- log_file(): Text 

## common.ab — import { ... } from "./common.ab"
- die(msg: Text): Null 
- require_cmd(name: Text, hint: Text): Null 
- on_signal_track_pid(pid: Text): Null 
- on_signal_untrack_pid(pid: Text): Null 
- on_signal_tracked_count(): Int 
- on_signal_cleanup(): Null 
- on_exit_register(fn_name: Text): Null 
- on_exit_registered_count(): Int 
- arch_os(): Text 
- migrate_home(): Text 
- stage(): Text 
- stage_dir(): Text 
- migrate_noninteractive(): Bool 
- common_reset_cache(): Null 
- stage_dir_init(): Null 
- common_harden(): Null 
- common_install_traps(): Null 

## version.ab — import { ... } from "./version.ab"
- cli_version(): Text 
- upgrade_notice_for_cli(): Null 
- version_check(): Null 
- ma_default_version(cached: Text): Text 

## artifacts.ab — import { ... } from "./artifacts.ab"
- artifacts_fetch(name: Text, version: Text): Text 
- artifacts_fetch_raw(name: Text, version: Text, repo_path: Text): Text 
- artifacts_reset_cache(): Null 

## discover.ab — import { ... } from "./discover.ab"
- disc_state_set(key: Text, val: Text): Null 
- disc_state_get(key: Text): Text 
- disc_state_has(key: Text): Bool 
- disc_state_reset(): Null 
- discover_os(): Int 
- discover_aws(): Int 
- discover_resources(): Int 

## cfn_outputs.ab — import { ... } from "./cfn_outputs.ab"
- _cfn_extract_exports(blob: Text): [Text] 
- cfn_outputs(stack: Text, region: Text): [Text] 
- cfn_output_value(stack: Text, region: Text, key: Text): Text 
- _cfn_pick(outputs: [Text], keys: [Text]): Text 

## manifest.ab — import { ... } from "./manifest.ab"
- manifest_init(): Null 
- manifest_have(): Bool 
- manifest_schema_ok(): Bool 
- manifest_path(): Text 
- manifest_brand(field: Text): Text 
- manifest_skills_dir(): Text 
- manifest_mcp_names(agent: Text): Text 
- manifest_mcp_field(name: Text, field: Text): Text 
- manifest_mcp_command(name: Text): Text 
- manifest_mcp_args(name: Text): Text 
- manifest_mcp_requires(name: Text): Text 
- manifest_mcp_perms(name: Text): Text 
- manifest_all_perms(): Text 
- manifest_pack_summary(): Text 
- manifest_build_version(): Text 
- manifest_modes(): Text 
- manifest_skills(): Text 

