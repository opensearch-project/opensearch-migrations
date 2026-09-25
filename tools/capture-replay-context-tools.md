# Capture/replay context tools

## Java code without limbo or traceability records

Validate marker structure and print one Java source file with every
`REBUILD-LIMBO-START(...)`/`END(...)` region and every `REBUILD-TRACE` record omitted:

```bash
tools/java-without-limbo.py print path/to/Source.java
```

Search executable-source lines across one or more Java files while retaining original line numbers:

```bash
tools/java-without-limbo.py search 'Owner|Mailbox' path/to/One.java path/to/Two.java
tools/java-without-limbo.py search -F 'literal text' path/to/Source.java
```

Search returns `0` for a match, `1` for no match, and `2` for invalid input or malformed, nested,
unmatched, or mismatched limbo/traceability scaffolding. Traceability records remain available to
reviewers through an explicit `rg 'REBUILD-TRACE'`; ordinary code searches omit them so a mapping
comment cannot be mistaken for a live symbol or caller. The tool never modifies source.

## Rebuild trace format

The trace baseline is executable behavior at mainline commit
`2fe4538aef16eafa098545e76545873335bf2d11`. A post-baseline method may be a target only for the inherited
baseline responsibility slice it received. Branch-only responsibilities with no mainline predecessor and
unchanged mainline responsibilities receive no trace.

Use exact method mappings on both sides:

```text
// REBUILD-TRACE(G5,source): OldOwner.submit(Request) -> NewOwner.submit(Request).
// REBUILD-TRACE(G5,target): OldOwner.submit(Request) -> NewOwner.submit(Request).
```

For a split, repeat the source and use one target per line:

```text
OldOwner.finish -> TurnOwner.finishTurn
OldOwner.finish -> RequestOwner.finishProcessing
```

For a substantial rewrite in the same method, name the method on both sides and state the rewritten
responsibility. For removal, use `OldClass.oldMethod -> RETIRED` and cite the design or recorded owner
decision. Do not use class-level summaries, wildcard phrases such as “constructors and helpers,” or
`oldMethod -> same-named live behavior`.

Before applying a phase-wide trace audit, commit or present three to five representative decisions and
obtain owner confirmation. A candidate rejected as unchanged or net-new is part of that sample even though
the correct source result is the absence or removal of a trace record.

Self-test:

```bash
tools/test-java-without-limbo.sh
```

## Compact Gradle evidence

Pass ordinary Gradle arguments directly to the wrapper:

```bash
tools/gradle-evidence.sh \
  :TrafficCapture:trafficReplayer:test \
  --tests '*KafkaSourceOwnerTest'
```

The wrapper injects `-x spotlessJavaCheck -x spotlessJavaApply` before caller arguments, preserves
each caller argument literally,
writes the full combined output to a unique `/private/tmp/gradle-evidence.*.log`, prints only a
concise success summary or failure excerpt, and exits with Gradle's status.

Self-test:

```bash
tools/test-gradle-evidence.sh
```
