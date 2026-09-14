# Asynchronous Message-Passing Programming Guide

**Status:** initial implementation guide

**Date:** 2026-09-14

This guide defines how Java components pass immutable inputs between state owners while preserving
typed completion paths. Component architecture documents define the messages, state, and required
ordering. This guide defines the programming pattern used to connect them.

The reusable classes are in `org.opensearch.migrations.utils.async`:

- `AsyncHandler`
- `AsyncReceiver`
- `AsyncLink`

## 1. Terms

A **state owner** is the only component permitted to mutate a particular set of state. Inputs may
arrive from several threads or asynchronous operations, but the owner applies them one at a time
using its existing execution environment.

An **owner input** is an immutable value submitted to that execution environment. For this
application, the execution environment is normally a Netty event loop or a thread-safe queue whose
only consumer is the owning Kafka thread.

A **handler** accepts one input and context and eventually produces one typed output.

A **receiver** accepts the output from a handler and the same context. It eventually produces the
result returned to the caller of the complete link.

An **asynchronous link** connects one handler to one compatible receiver. If the handler completes
normally, the link invokes the receiver automatically. The stage returned by the link is the
receiver's stage, not the handler's intermediate stage.

## 2. Governing rules

1. Mutable correctness state has one owner.
2. The component design names every input that can change that state and any ordering required
   among those inputs.
3. Cross-owner inputs and contexts are immutable.
4. Expected operation outcomes are explicit values, preferably sealed types.
5. Unexpected throws and exceptional stage completions are process-fatal.
6. A typed completion required by another owner is delivered through an `AsyncLink`.
7. An operation may omit a returned domain value only when no correctness decision, cleanup,
   resource release, or required later action depends on it.
8. The owner never blocks its event loop or queue-draining thread while asynchronous work is
   pending.

## 3. Start with the synchronous form

Write the intended data flow as synchronous code first:

```java
Output output = destinationOwner.handle(input, context);
Result result = nextOwner.receive(output, context);
return result;
```

The asynchronous interfaces preserve that shape:

```java
CompletionStage<Output> output = handler.handle(input, context);
return output.thenCompose(value -> receiver.receive(value, context));
```

`AsyncLink` packages the composition and fixes the compatible types:

```java
var link = new AsyncLink<Input, Output, Context, Result>(handler);

CompletionStage<Result> completion =
    link.receive(input, receiver, context);
```

Changing the handler's output type or supplying an incompatible receiver causes a compilation
error.

## 4. Submit work to the existing owner

`AsyncLink` does not choose a thread. A handler or receiver that changes owner-confined state must
submit its operation to that owner's existing execution environment.

For a Netty-owned component, the adapter submits to the selected event loop:

```text
immutable input
    -> eventLoop.execute(owner operation)
    -> owner changes its state
    -> returned stage completes with a typed value
```

For replay intake, producers append immutable inputs to a thread-safe queue and wake the Kafka
executor:

```text
Netty or tuple completion
    -> ConcurrentLinkedQueue.add(immutable owner input)
    -> wake Kafka executor
    -> Kafka executor, as the sole consumer, applies queued inputs one at a time
```

The concurrent queue makes submission thread-safe. It does not permit producers to mutate replay
intake state. If inputs require a total order, the component design must establish that order
before or while admitting them; the queue type alone is not the proof.

The replayer therefore uses:

- the dedicated Kafka executor for Kafka consumer state;
- a thread-safe replay-intake input queue drained only by the Kafka executor;
- each connection's selected Netty event loop for connection and request-replay state; and
- the tuple writer's existing asynchronous I/O.

No additional executor or owner thread is introduced by this programming pattern.

## 5. Required result chains

The thread completing a handler's stage may invoke the receiver function. A receiver that owns
mutable state must therefore submit its operation to its owner before changing that state.

The stage returned by `AsyncLink` completes only after the receiver's returned stage completes.
Normal completion therefore represents the whole linked operation:

```text
handler accepted input
    -> handler produced output
    -> receiver accepted output
    -> receiver produced its result
    -> linked stage completed
```

This does not prove that an asynchronous operation eventually terminates. It does ensure that a
normally produced typed output cannot bypass the required receiver.

Code may submit an input without a typed receiver only for work such as best-effort diagnostics
whose completion cannot affect:

- request acceptance or completion;
- cleanup responsibility;
- resource release;
- Kafka-record accounting;
- tuple durability; or
- any required subsequent action.

## 6. Expected outcomes and unexpected failures

Expected outcomes belong in sealed interfaces, records, or equivalent exhaustive value types:

```java
sealed interface StartResult permits Accepted, Rejected {}

record Accepted(RequestId requestId) implements StartResult {}

record Rejected(RequestId requestId, String reason) implements StartResult {}
```

Code consuming `StartResult` uses an exhaustive switch. Adding another result then causes affected
switches to stop compiling until they handle it.

Do not use an exception to represent an expected rejection, retry, expiration, revocation, target
response, or commit result. Catch the operation's expected failures and return the applicable typed
value.

A synchronous throw, null stage, exceptional completion, rejected required submission, or owner
thread violation is unexpected. The adapter to the Netty event loop, Kafka executor, or other
owner reports it to the application process supervisor. Production does not recover the failed
owner and continue.

## 7. Ordering

Submitting owner operations in order controls when those operation bodies begin. It does not force
asynchronous results to finish in that order:

```text
owner applies A -> A starts asynchronous work
owner applies B -> B finishes
A finishes later -> A's typed result returns as a new owner input
```

Any asynchronous result that changes owner state returns as another immutable owner input. Network,
timer, and completion callbacks do not mutate unrelated owner state directly.

Source and target ordering guarantees come from the owning component's state machine and its
explicit queues or ordinals, not from completion timing.

## 8. Scoped cancellation

Cancellation is a typed component input. It identifies its scope, such as one Kafka partition
generation.

The owner receives that input through its normal submission path, finds matching work in its own
state, and begins the component-defined cleanup. The Netty event loop, Kafka executor, and shared
replay-intake queue remain active so unrelated partition generations can continue.

Do not implement scoped cancellation by killing an owner thread, replacing unrelated queued
inputs, or relying only on shared mutable state. If another owner must wait for cleanup, the
cancellation path returns a typed cleanup result through a required link.

## 9. Operation lifetime and observability

Types cannot prove that an asynchronous operation eventually completes. For correctness-relevant
long-running work, the owner must:

1. register the operation before sending or starting it;
2. keep the registration until it processes the applicable typed completion;
3. expose enough identity for the activity monitor to report it; and
4. clear the registration only while processing that completion.

## 10. Patterns to avoid

Do not:

- mutate owner state from another thread;
- use detached callbacks for correctness-required actions;
- convert unexpected exceptions into ordinary domain success;
- ignore a returned stage when another action depends on its result;
- block a Netty event loop or the Kafka executor on `Future.get`, `join`, a semaphore, or network
  I/O outside the Kafka client's required blocking calls;
- assume asynchronous completion order matches submission order;
- pass mutable state through an owner input; or
- add default interface methods that silently choose completion or failure behavior.

## 11. Testing a new message path

Tests for a linked path should prove:

1. normal handler output reaches the required receiver exactly once within the running process;
2. the receiver's result reaches the original caller;
3. an incorrect output and receiver pairing does not compile;
4. each declared result variant is handled exhaustively;
5. handler and receiver exceptions remain exceptional and reach the process supervisor;
6. the owner applies admitted inputs in the order required by the component design;
7. pending asynchronous work does not block the owner from applying unrelated inputs; and
8. long-running work remains visible to the activity monitor.

The focused utility tests are in
`coreUtilities/src/test/java/org/opensearch/migrations/utils/async/AsyncLinkTest.java`.

Run them with:

```shell
./gradlew :coreUtilities:test \
  --tests org.opensearch.migrations.utils.async.AsyncLinkTest
```
