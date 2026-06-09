// main.rs — terminal event-loop binary.
//
// This is the ONLY file allowed to do real I/O (terminal setup/teardown,
// crossterm event polling, async service stubs). Everything it touches
// flows back into the App as a Message or RuntimeEvent.
//
// Two extension stubs are wired in here for the demo:
//   * a hand-built ApprovalGate list   (so the approvals panel has data)
//   * a synthesised WorkflowStatus     (so the workflow panel has data)
// Real impls would be tokio-spawned tasks publishing RuntimeEvents through
// a channel.

use std::io;
use std::time::Duration;

use crossterm::{
    event::{self, DisableMouseCapture, EnableMouseCapture, Event, KeyCode, KeyEventKind},
    execute,
    terminal::{disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen},
};
use ratatui::{Terminal, backend::CrosstermBackend};

use migration_tui::domain::app::App;
use migration_tui::domain::message::Message;
use migration_tui::domain::runtime::RuntimeEvent;
use migration_tui::extension::config::ConfigDoc;
use migration_tui::extension::workflow::{
    ApprovalGate, GateKind, NodeType, WorkflowNode, WorkflowPhase, WorkflowStatus,
};
use migration_tui::tui::render;

fn main() -> io::Result<()> {
    // CLI handling — only flags that don't need the TUI.
    // Anything more complex than this (subcommands, --config <file>,
    // etc.) should pull in clap. For now, --version and --help cover
    // the entire surface and we keep the dep footprint minimal.
    let mut args = std::env::args().skip(1);
    if let Some(arg) = args.next() {
        match arg.as_str() {
            "--version" | "-V" => {
                println!("migration-tui {}", migration_tui::version::resolve_version());
                return Ok(());
            }
            "--help" | "-h" => {
                println!("migration-tui — terminal UI for OpenSearch migrations");
                println!();
                println!("Usage: migration-tui [OPTIONS]");
                println!();
                println!("Options:");
                println!("  -V, --version    Print the installed version (from VERSION.txt)");
                println!("  -h, --help       Print this help message");
                return Ok(());
            }
            other => {
                eprintln!("migration-tui: unknown argument: {other}");
                eprintln!("Try 'migration-tui --help' for usage.");
                std::process::exit(2);
            }
        }
    }

    enable_raw_mode()?;
    let mut stdout = io::stdout();
    execute!(stdout, EnterAlternateScreen, EnableMouseCapture)?;
    let mut terminal = Terminal::new(CrosstermBackend::new(stdout))?;

    // Seed the App with mock data so the panels have something to show.
    let mut app = App::default()
        .dispatch(RuntimeEvent::WorkflowStatusReceived(seed_workflow()))
        .dispatch(RuntimeEvent::GatesReceived(seed_gates()))
        .dispatch(RuntimeEvent::ConfigLoaded(ConfigDoc {
            yaml: "source:\n  endpoint: https://es:9200\ntarget:\n  endpoint: https://os:9200\n".into(),
        }));

    let res = run_loop(&mut terminal, &mut app);

    disable_raw_mode()?;
    execute!(terminal.backend_mut(), LeaveAlternateScreen, DisableMouseCapture)?;
    terminal.show_cursor()?;

    res
}

fn run_loop(
    terminal: &mut Terminal<CrosstermBackend<io::Stdout>>,
    app: &mut App,
) -> io::Result<()> {
    loop {
        terminal.draw(|f| render::render(f, app))?;
        if app.should_quit { return Ok(()); }

        if event::poll(Duration::from_millis(100))? {
            if let Some(msg) = read_message()? {
                let next = std::mem::take(app).update(msg);
                *app = next;
            }
        }
    }
}

fn read_message() -> io::Result<Option<Message>> {
    match event::read()? {
        Event::Resize(w, h) => Ok(Some(Message::Resize(w, h))),
        Event::Key(k) if k.kind == KeyEventKind::Press => {
            Ok(match k.code {
                KeyCode::Char(c) => Some(Message::Char(c)),
                KeyCode::Backspace => Some(Message::Backspace),
                KeyCode::Enter => Some(Message::Enter),
                KeyCode::Esc => Some(Message::Escape),
                KeyCode::Tab => Some(Message::Tab),
                KeyCode::Up => Some(Message::Up),
                KeyCode::Down => Some(Message::Down),
                _ => None,
            })
        }
        _ => Ok(None),
    }
}

// ── seed data ──────────────────────────────────────────────────────────

fn seed_workflow() -> WorkflowStatus {
    fn n(id: &str, depth: u32, phase: WorkflowPhase, kids: Vec<WorkflowNode>) -> WorkflowNode {
        WorkflowNode {
            id: id.into(), name: id.into(), display_name: id.into(),
            phase, node_type: NodeType::StepGroup,
            started_at: None, finished_at: None,
            depth, parent: None, children: kids,
        }
    }
    WorkflowStatus {
        workflow_name: "demo-migration".into(),
        namespace: "ma".into(),
        phase: WorkflowPhase::Running,
        progress: Some("2/4".into()),
        started_at: None, finished_at: None,
        step_tree: vec![
            n("snapshot",   0, WorkflowPhase::Succeeded, vec![]),
            n("metadata",   0, WorkflowPhase::Succeeded, vec![]),
            n("backfill",   0, WorkflowPhase::Running,   vec![
                n("worker-1", 1, WorkflowPhase::Running, vec![]),
                n("worker-2", 1, WorkflowPhase::Running, vec![]),
            ]),
            n("verify",     0, WorkflowPhase::Pending,   vec![]),
        ],
        error: None,
    }
}

fn seed_gates() -> Vec<ApprovalGate> {
    vec![
        ApprovalGate {
            name: "review-target-mapping".into(),
            kind: GateKind::Step,
            blockers: vec![],
        },
        ApprovalGate {
            name: "schema-change-approval".into(),
            kind: GateKind::Change,
            blockers: vec!["index settings drift detected".into()],
        },
    ]
}
