# Ported module public APIs (import targets for tests + the entrypoint)

## core.ab
- term_detect(): Bool 
- term_interactive(): Bool 
- esc(): Text 
- eprint(s: Text): Null 
- eprintln(s: Text): Null 

## std.ab
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

## term.ab
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

## ui.ab
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

## dashboard.ab
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

## timeline.ab
- timeline_phase_label(key: Text): Text 
- timeline_index_of(last: Text): Int 
- timeline_render(last: Text): Text 

## state.ab
- state_load(): Null 
- state_set(k: Text, v: Text): Null 
- state_get(k: Text, def: Text): Text 
- state_has(k: Text): Bool 
- state_unset(k: Text): Null 
- state_count(): Int 
- state_save(): Null 
- state_archive(): Null 
- state_resumable_step(): Text 

## log.ab
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

## common.ab
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

## version.ab
- cli_version(): Text 
- upgrade_notice_for_cli(): Null 
- version_check(): Null 
- ma_default_version(cached: Text): Text 

## artifacts.ab
- artifacts_fetch(name: Text, version: Text): Text 
- artifacts_fetch_raw(name: Text, version: Text, repo_path: Text): Text 
- artifacts_reset_cache(): Null 

## discover.ab
- disc_state_set(key: Text, val: Text): Null 
- disc_state_get(key: Text): Text 
- disc_state_has(key: Text): Bool 
- disc_state_reset(): Null 
- discover_os(): Int 
- discover_aws(): Int 
- discover_resources(): Int 

## cfn_outputs.ab
- _cfn_extract_exports(blob: Text): [Text] 
- cfn_outputs(stack: Text, region: Text): [Text] 
- cfn_output_value(stack: Text, region: Text, key: Text): Text 
- _cfn_pick(outputs: [Text], keys: [Text]): Text 

## manifest.ab
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

## wizard.ab
- _wizard_offer_existing_stack(list: Text): Text 
- wizard_collect(): Null 

## cfn_deploy.ab
- _cfn_stack_healthy(stack: Text, region: Text): Bool 
- _cfn_subnet_route_table_ids(vpc_id: Text, subnets_csv: Text, region: Text): Text 
- _cfn_import_vpc_endpoint_params(vpc_id: Text, subnets_csv: Text, region: Text, endpoints_csv: Text): [Text] 
- _cfn_tail_events(stack: Text, region: Text, deploy_pid: Text): Int 
- cfn_deploy_or_skip(): Int 

## crane.ab
- _dst_for(src: Text, ecr_host: Text): Text 
- _ecr_repo_for(src: Text): Text 
- _crane_copy_retry(src: Text, dst: Text): Int 
- _crane_load_manifest(ma_ver: Text): Text 
- _ecr_login(registry: Text, region: Text): Int 
- _crane_mirror_ma_images(registry: Text, ma_ver: Text, region: Text): Int 
- crane_mirror_or_skip(): Int 

## build.ab
- _build_resolve_base_dir(): Text 
- _build_builder_name(ctx: Text): Text 
- _build_ecr_login(registry: Text, region: Text): Int 
- _build_setup_buildkit(builder: Text, base_dir: Text): Int 
- _build_gradle_invoke(base_dir: Text, registry: Text, builder: Text, image_tag: Text, skip_flag: Text): Int 
- build_images_or_skip(): Int 

## helm_ctx.ab
- helm_ctx_init(): Null 
- helm_kctx_init(): Null 
- helm_ctx_flags(): Text 
- kubectl_ctx_flags(): Text 
- helm_kubeconfig_setup(): Null 

## helm_recover.ab
- _helm_release_status(release: Text, ns: Text): Text 
- _helm_uninstall_quiet(release: Text, ns: Text): Null 
- _helm_clear_stuck_revision(release: Text, ns: Text): Bool 
- helm_recover_orphan_jobs(release: Text, ns: Text): Null 
- _helm_wait_namespace_settled(ns: Text, timeout_s: Int): Bool 
- helm_ensure_namespace(ns: Text): Null 
- helm_recover_if_stuck(release: Text, ns: Text): Int 

## helm_diag.ab
- _helm_inst_log(msg: Text): Null 
- _helm_pods_log(msg: Text): Null 
- _helm_dump_failed_job(job: Text, ns: Text): Null 
- _helm_explain_failure(release: Text, ns: Text): Null 
- helm_dump_diagnostics(release: Text, ns: Text): Null 
- _helm_inst_dump_pending_pod(pod: Text, ns: Text): Null 
- _helm_dump_install_notes(release: Text, ns: Text): Null 
- helm_pods_summary(snapshot: Text): Text 
- helm_dump_pods(ns: Text): Null 

## helm_install.ab
- _helm_extract_chart_values(chart: Text): Text 
- _write_helm_values(out: Text): Null 
- _helm_build_public_image_flags(ver: Text): [Text] 
- _helm_build_mirrored_image_flags(registry: Text, ver: Text): [Text] 
- _helm_tls_flags(): [Text] 
- _helm_apply_disable_general_purpose_pool(cluster: Text, region: Text): Int 
- helm_watch_pods(ns: Text): Text 
- helm_watch_installer_logs(release: Text, ns: Text): Null 
- helm_install_or_upgrade(): Int 

## console.ab
- _console_exec_into_pod(ns: Text): Int 
- cmd_console(argv: [Text]): Int 
- console_exec(): Int 
- cmd_diag(argv: [Text]): Int 

## agent.ab
- _agent_bin_for(canonical: Text): Text 
- _agent_bins_for(canonical: Text): Text 
- _mcp_norm_envvar(name: Text): Text 
- _mcp_pretty(p: Text): Text 
- _mcp_write_claude(name: Text): Null 
- _mcp_write_codex(name: Text): Null 
- _mcp_write_kiro(name: Text): Null 
- _agent_write_claude_settings(): Null 
- _agent_print_install_hints_for_missing(installed: [Text]): Null 
- _agent_has_session(agent: Text): Bool 
- discover_agents(): [Text] 
- mcp_install_from_manifest(agent: Text): Null 
- agent_setup(agent: Text): Null 
- agent_exec(agent: Text): Null 
- agent_path(): Null 
- cmd_agent(args: [Text]): Null 

## cleanup.ab
- _cmd_clear_agent_paths_for(sdir: Text): [Text] 
- cmd_cleanup(argv: [Text]): Int 
- cmd_clear(argv: [Text]): Int 

## pack.ab
- _pack_resolve_root(stage: Text): Text 
- _pack_add_skill(root: Text, skill_dir: Text): Null 
- _pack_add_mcp(manifest: Text, frag: Text): Null 
- _pack_apply_branding(manifest: Text, frag: Text): Null 
- _pack_apply_brand_flags(manifest: Text, name: Text, binary: Text, tagline: Text, mode_default: Text, mode_order: Text): Null 
- _pack_validate(manifest: Text, strict: Int): Bool 
- _pack_append_entry(manifest: Text, name: Text, ver: Text, skills_csv: Text, mcps_csv: Text, branding_changed: Int): Null 
- _pack_print_help(): Null 
- cmd_pack(argv: [Text]): Int 

## resume.ab
- _select_mode(current: Text): Text 
- _manual_can_skip_to_console(): Bool 
- manual_path(): Int 
- cmd_resume(args: [Text]): Int 
- _help_body(): Text 
- cmd_help(): Null 

## install.ab
- fetch_into(install_dir: Text, version: Text, repo: Text, from_local: Text): Null 
- read_binary_name(install_dir: Text): Text 
- contains_path(path: Text, dir: Text): Bool 

