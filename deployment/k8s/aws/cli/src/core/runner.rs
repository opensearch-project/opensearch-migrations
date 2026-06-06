//! The external-command seam.
//!
//! Every shell-out (`aws`, `kubectl`, `helm`, `crane`, `docker`, `curl`) goes
//! through [`CommandRunner`]. Production uses [`RealRunner`], which spawns the
//! process; tests use [`MockRunner`], which matches argument patterns, returns
//! scripted output, and records each invocation for later assertions.
//!
//! This is the single abstraction that makes the orchestration layer
//! (discover / cfn / crane / helm / build / console / agent / cleanup)
//! unit-testable without touching real AWS.

use std::sync::Mutex;

/// The captured result of running one external command.
#[derive(Debug, Clone)]
pub struct Output {
    pub status: i32,
    pub stdout: String,
    pub stderr: String,
}

impl Output {
    /// Whether the command exited 0.
    pub fn success(&self) -> bool {
        self.status == 0
    }

    /// `stdout` with the trailing newline trimmed — the common case when a
    /// command prints a single value (an ARN, a status, a cluster name).
    pub fn trimmed_stdout(&self) -> &str {
        self.stdout.trim_end_matches('\n')
    }
}

/// Abstraction over running an external program with arguments.
///
/// Implementors must be `Send + Sync` so a single runner can be shared across
/// the orchestrator and any background watchers.
pub trait CommandRunner: Send + Sync {
    /// Run `program` with `args`, capturing stdout/stderr and the exit status.
    ///
    /// Implementations should NOT treat a non-zero exit as an error of this
    /// method — they return [`Output`] with the real status, and callers
    /// decide. (Mirrors the bash `set +e; cmd; rc=$?` idiom.)
    fn run(&self, program: &str, args: &[&str]) -> Output;

    /// Run `program` with `args` and extra environment variables set for the
    /// child process. Used by the Gradle delegations (CDK synth, image build),
    /// which need `STACK_NAME_SUFFIX`/`MULTI_ARCH_NATIVE` in the child env. The
    /// default impl ignores `env` and calls [`run`](Self::run) so existing
    /// implementors stay valid; [`RealRunner`] overrides it to actually set them.
    fn run_with_env(&self, program: &str, args: &[&str], _env: &[(&str, &str)]) -> Output {
        self.run(program, args)
    }

    /// Run `program` while STREAMING its stdout/stderr to this process's stderr
    /// line by line (so a long deploy step — `cloudformation deploy`, the gradle
    /// CDK synth / image build, `helm install --wait` — shows live progress on
    /// the Jenkins console instead of freezing until it returns), AND still
    /// capturing the output into the returned [`Output`]. Each streamed line is
    /// prefixed with `tag` (e.g. `cfn`, `helm`) so interleaved output is legible.
    ///
    /// The default impl ignores streaming and delegates to
    /// [`run_with_env`](Self::run_with_env) — so [`MockRunner`] records the call
    /// exactly as before and tests are unchanged; only [`RealRunner`] streams.
    fn run_streamed(
        &self,
        program: &str,
        args: &[&str],
        env: &[(&str, &str)],
        _tag: &str,
    ) -> Output {
        self.run_with_env(program, args, env)
    }

    /// Whether `program` resolves on PATH — `command -v` / `optional_cmd`.
    fn has_command(&self, program: &str) -> bool;

    /// Convenience: run and return `true` iff the command exited 0.
    fn run_ok(&self, program: &str, args: &[&str]) -> bool {
        self.run(program, args).success()
    }
}

/// Production runner: spawns real processes.
#[derive(Debug, Default, Clone, Copy)]
pub struct RealRunner;

impl CommandRunner for RealRunner {
    fn run(&self, program: &str, args: &[&str]) -> Output {
        self.run_with_env(program, args, &[])
    }

    fn run_with_env(&self, program: &str, args: &[&str], env: &[(&str, &str)]) -> Output {
        let mut cmd = std::process::Command::new(program);
        cmd.args(args);
        for (k, v) in env {
            cmd.env(k, v);
        }
        match cmd.output() {
            Ok(out) => Output {
                status: out.status.code().unwrap_or(-1),
                stdout: String::from_utf8_lossy(&out.stdout).into_owned(),
                stderr: String::from_utf8_lossy(&out.stderr).into_owned(),
            },
            Err(e) => Output {
                // 127 mirrors the shell "command not found" exit code.
                status: 127,
                stdout: String::new(),
                stderr: format!("failed to spawn {program}: {e}"),
            },
        }
    }

    fn run_streamed(
        &self,
        program: &str,
        args: &[&str],
        env: &[(&str, &str)],
        tag: &str,
    ) -> Output {
        use std::io::{BufRead, BufReader, Write};
        use std::process::Stdio;

        let mut cmd = std::process::Command::new(program);
        cmd.args(args).stdout(Stdio::piped()).stderr(Stdio::piped());
        for (k, v) in env {
            cmd.env(k, v);
        }
        let mut child = match cmd.spawn() {
            Ok(c) => c,
            Err(e) => {
                return Output {
                    status: 127,
                    stdout: String::new(),
                    stderr: format!("failed to spawn {program}: {e}"),
                }
            }
        };

        // Drain stdout and stderr concurrently so a chatty child can't deadlock
        // on a full pipe. Each reader tees lines to OUR stderr (prefixed with the
        // tag) for live progress, and accumulates them for the captured Output.
        let out_pipe = child.stdout.take();
        let err_pipe = child.stderr.take();
        let tag_owned = tag.to_string();
        let tag2 = tag_owned.clone();

        let stdout_handle = std::thread::spawn(move || {
            let mut buf = String::new();
            if let Some(p) = out_pipe {
                for line in BufReader::new(p).lines().map_while(Result::ok) {
                    let err = std::io::stderr();
                    let _ = writeln!(err.lock(), "  {tag_owned}│ {line}");
                    buf.push_str(&line);
                    buf.push('\n');
                }
            }
            buf
        });
        let stderr_handle = std::thread::spawn(move || {
            let mut buf = String::new();
            if let Some(p) = err_pipe {
                for line in BufReader::new(p).lines().map_while(Result::ok) {
                    let err = std::io::stderr();
                    let _ = writeln!(err.lock(), "  {tag2}│ {line}");
                    buf.push_str(&line);
                    buf.push('\n');
                }
            }
            buf
        });

        let status = child.wait().ok().and_then(|s| s.code()).unwrap_or(-1);
        let stdout = stdout_handle.join().unwrap_or_default();
        let stderr = stderr_handle.join().unwrap_or_default();
        Output {
            status,
            stdout,
            stderr,
        }
    }

    fn has_command(&self, program: &str) -> bool {
        // `which`-free PATH scan so we don't depend on an external tool.
        let Ok(path) = std::env::var("PATH") else {
            return false;
        };
        std::env::split_paths(&path).any(|dir| {
            let candidate = dir.join(program);
            candidate.is_file() && is_executable(&candidate)
        })
    }
}

#[cfg(unix)]
fn is_executable(path: &std::path::Path) -> bool {
    use std::os::unix::fs::PermissionsExt;
    std::fs::metadata(path)
        .map(|m| m.permissions().mode() & 0o111 != 0)
        .unwrap_or(false)
}

#[cfg(not(unix))]
fn is_executable(_path: &std::path::Path) -> bool {
    true
}

/// One scripted response in a [`MockRunner`]: when the joined `program + args`
/// string contains every substring in `match_all`, reply with this output.
#[derive(Debug, Clone)]
struct Stub {
    program: String,
    match_all: Vec<String>,
    output: Output,
}

/// A recorded invocation: the program and its joined arguments.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Call {
    pub program: String,
    pub args: Vec<String>,
}

impl Call {
    /// The argument vector joined by spaces — convenient for `contains`
    /// assertions on a recorded invocation.
    pub fn joined(&self) -> String {
        self.args.join(" ")
    }
}

/// Test runner: matches argument patterns, returns scripted output, and records
/// every call. The default reply (no matching stub) is exit 0 with empty
/// output, so an un-stubbed command is a benign no-op.
#[derive(Default)]
pub struct MockRunner {
    stubs: Mutex<Vec<Stub>>,
    commands: Mutex<Vec<String>>,
    calls: Mutex<Vec<Call>>,
    default_status: Mutex<i32>,
}

impl MockRunner {
    /// A fresh mock with no stubs and no known commands.
    pub fn new() -> Self {
        Self::default()
    }

    /// Register `program` as present on PATH for [`CommandRunner::has_command`].
    pub fn with_command(self, program: &str) -> Self {
        self.commands.lock().unwrap().push(program.to_string());
        self
    }

    /// Add a stub: when an invocation of `program` has arguments whose joined
    /// form contains all of `match_all`, return `(status, stdout)`.
    ///
    /// Stubs are matched in registration order; the first match wins. Register
    /// the most specific patterns first.
    pub fn stub(self, program: &str, match_all: &[&str], status: i32, stdout: &str) -> Self {
        self.stubs.lock().unwrap().push(Stub {
            program: program.to_string(),
            match_all: match_all.iter().map(|s| s.to_string()).collect(),
            output: Output {
                status,
                stdout: stdout.to_string(),
                stderr: String::new(),
            },
        });
        self
    }

    /// Add a stub that returns on stderr instead of stdout — used to exercise
    /// error-classification paths (crane fatal-error detection, ecr-login).
    pub fn stub_stderr(self, program: &str, match_all: &[&str], status: i32, stderr: &str) -> Self {
        self.stubs.lock().unwrap().push(Stub {
            program: program.to_string(),
            match_all: match_all.iter().map(|s| s.to_string()).collect(),
            output: Output {
                status,
                stdout: String::new(),
                stderr: stderr.to_string(),
            },
        });
        self
    }

    /// Set the status returned when no stub matches (default 0).
    pub fn default_status(self, status: i32) -> Self {
        *self.default_status.lock().unwrap() = status;
        self
    }

    /// Every recorded call, in order.
    pub fn calls(&self) -> Vec<Call> {
        self.calls.lock().unwrap().clone()
    }

    /// Recorded calls to a specific program, in order — analog of `stub_calls`.
    pub fn calls_to(&self, program: &str) -> Vec<Call> {
        self.calls()
            .into_iter()
            .filter(|c| c.program == program)
            .collect()
    }

    /// Whether any recorded call's joined `program args` contains `needle`.
    pub fn any_call_contains(&self, needle: &str) -> bool {
        self.calls()
            .iter()
            .any(|c| format!("{} {}", c.program, c.joined()).contains(needle))
    }
}

impl CommandRunner for MockRunner {
    fn run(&self, program: &str, args: &[&str]) -> Output {
        let owned: Vec<String> = args.iter().map(|s| s.to_string()).collect();
        self.calls.lock().unwrap().push(Call {
            program: program.to_string(),
            args: owned,
        });

        let joined = format!("{} {}", program, args.join(" "));
        let stubs = self.stubs.lock().unwrap();
        for stub in stubs.iter() {
            if stub.program == program && stub.match_all.iter().all(|m| joined.contains(m.as_str()))
            {
                return stub.output.clone();
            }
        }
        Output {
            status: *self.default_status.lock().unwrap(),
            stdout: String::new(),
            stderr: String::new(),
        }
    }

    fn has_command(&self, program: &str) -> bool {
        self.commands.lock().unwrap().iter().any(|c| c == program)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mock_returns_matching_stub() {
        let r = MockRunner::new().stub(
            "aws",
            &["sts", "get-caller-identity"],
            0,
            r#"{"Account":"123"}"#,
        );
        let out = r.run("aws", &["sts", "get-caller-identity", "--output", "json"]);
        assert!(out.success());
        assert!(out.stdout.contains("123"));
    }

    #[test]
    fn mock_first_match_wins() {
        let r = MockRunner::new()
            .stub("helm", &["status", "ma"], 0, "deployed")
            .stub("helm", &["status"], 0, "other");
        assert_eq!(
            r.run("helm", &["status", "ma", "-o", "json"])
                .trimmed_stdout(),
            "deployed"
        );
    }

    #[test]
    fn mock_records_calls_in_order() {
        let r = MockRunner::new();
        r.run("kubectl", &["get", "pods"]);
        r.run("helm", &["upgrade", "--install", "ma"]);
        let calls = r.calls();
        assert_eq!(calls.len(), 2);
        assert_eq!(calls[0].program, "kubectl");
        assert_eq!(calls[1].joined(), "upgrade --install ma");
        assert!(r.any_call_contains("helm upgrade --install ma"));
    }

    #[test]
    fn mock_default_status_when_no_stub() {
        let r = MockRunner::new().default_status(1);
        assert_eq!(r.run("aws", &["whatever"]).status, 1);
    }

    #[test]
    fn mock_has_command() {
        let r = MockRunner::new().with_command("tar");
        assert!(r.has_command("tar"));
        assert!(!r.has_command("crane"));
    }
}
