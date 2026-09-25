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
