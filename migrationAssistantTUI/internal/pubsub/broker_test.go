package pubsub_test

import (
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/pubsub"
)

// TestBroker_PublishFanout — a single Publish must reach every active
// subscriber. This is the basic contract.
func TestBroker_PublishFanout(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	chA, _ := b.Subscribe()
	chB, _ := b.Subscribe()

	b.Publish("hello")

	require.Equal(t, "hello", recv(t, chA))
	require.Equal(t, "hello", recv(t, chB))
}

// TestBroker_Unsubscribe — after Unsubscribe, the subscriber's channel is
// closed and no further messages reach it. The other subscriber is
// unaffected. This guards against the most common bug: leaked subscribers
// surviving page tear-down.
func TestBroker_Unsubscribe(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	chA, unsubA := b.Subscribe()
	chB, _ := b.Subscribe()

	unsubA()

	// Channel A is closed; reading returns zero-value with ok=false.
	_, ok := <-chA
	require.False(t, ok, "after Unsubscribe, channel must be closed")

	// Channel B still receives.
	b.Publish("post-unsub")
	require.Equal(t, "post-unsub", recv(t, chB))
}

// TestBroker_UnsubscribeIdempotent — calling unsub twice (e.g. from a
// deferred cleanup AND from an explicit teardown path) MUST NOT panic.
// This protects bubbletea's tea.Sub teardown, which can race with our
// own cleanup.
func TestBroker_UnsubscribeIdempotent(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	_, unsub := b.Subscribe()

	require.NotPanics(t, func() {
		unsub()
		unsub()
		unsub()
	})
}

// TestBroker_Close — Close terminates every subscription, and subsequent
// Publish calls are no-ops (do not panic, do not block).
func TestBroker_Close(t *testing.T) {
	b := pubsub.NewBroker()

	chA, _ := b.Subscribe()
	chB, _ := b.Subscribe()

	b.Close()

	_, ok := <-chA
	require.False(t, ok)
	_, ok = <-chB
	require.False(t, ok)

	// Publish after Close must not panic.
	require.NotPanics(t, func() { b.Publish("after-close") })

	// Close after Close must not panic.
	require.NotPanics(t, b.Close)

	// Subscribe after Close returns a closed channel and a no-op unsub.
	chC, unsubC := b.Subscribe()
	_, ok = <-chC
	require.False(t, ok)
	require.NotPanics(t, unsubC)
}

// TestBroker_SlowConsumerDoesNotBlockFast — the load-bearing property.
// A deliberately-stalled "slow" consumer must not block Publish; the
// fast consumer must keep progressing and the broker must record drops
// on the slow side. Without this, a hung render goroutine would
// deadlock the deploy goroutine and the UI would freeze with no
// diagnostic.
//
// Note on what we DON'T assert: that the fast consumer receives every
// published message. Under -race with package-wide load, the publisher
// loop can race ahead of the fast reader, fill its 64-element buffer,
// and drop on the fast side too. That's correct broker behavior under
// the non-blocking-publisher contract — assertion target is "no
// deadlock + fast made meaningful progress + slow caused drops",
// NOT "exactly N delivered".
func TestBroker_SlowConsumerDoesNotBlockFast(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	slow, _ := b.Subscribe() // intentionally never read
	fast, _ := b.Subscribe()

	const N = 1000 // bigger than buffer so we're guaranteed to exercise drops
	var fastReceived atomic.Int64
	stop := make(chan struct{})
	go func() {
		for {
			select {
			case _, ok := <-fast:
				if !ok {
					return
				}
				fastReceived.Add(1)
			case <-stop:
				return
			}
		}
	}()

	publishDone := make(chan struct{})
	go func() {
		defer close(publishDone)
		for i := 0; i < N; i++ {
			b.Publish(i)
		}
	}()

	// Publisher must NEVER block — even though slow consumer is hung.
	select {
	case <-publishDone:
	case <-time.After(5 * time.Second):
		t.Fatalf("Publish stalled — slow consumer is blocking publisher")
	}

	// Fast consumer must have made meaningful progress (proving it
	// wasn't blocked behind the slow one). Allow scheduler slack: at
	// least one buffer-full's worth.
	require.Eventually(t,
		func() bool { return fastReceived.Load() >= 64 },
		2*time.Second, 10*time.Millisecond,
		"fast consumer made no progress (got %d, want >=64)",
		fastReceived.Load())
	close(stop)

	// Slow consumer's buffered channel had a buffer's worth queued and
	// the rest were dropped — that's the proof Publish chose drop over
	// block.
	require.Greater(t, b.DropCount(), int64(0),
		"slow consumer should have caused at least one drop")

	_ = slow // silence unused
}

// TestBroker_PublishConcurrent — concurrent publishers must not corrupt
// the broker state. Run with -race.
func TestBroker_PublishConcurrent(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	ch, _ := b.Subscribe()

	const goroutines = 8
	const perGoroutine = 50
	var wg sync.WaitGroup
	for g := 0; g < goroutines; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < perGoroutine; i++ {
				b.Publish(i)
			}
		}()
	}
	wg.Wait()

	// Drain to avoid leak; we do not assert exact count because slow-buffer
	// drops are part of the contract.
	go func() {
		for range ch {
		}
	}()
}

// TestBroker_SubscribeFromPublisher — re-entrant Subscribe inside the
// goroutine that just published must not deadlock. Common pattern: a
// page reacts to an event by spawning a sub-poller that also wants to
// listen. Holding a write lock during fan-out would deadlock here.
func TestBroker_SubscribeFromPublisher(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	ch1, _ := b.Subscribe()
	done := make(chan struct{})
	go func() {
		<-ch1 // wait for first message
		ch2, _ := b.Subscribe()
		_ = ch2
		close(done)
	}()

	b.Publish("trigger")

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("re-entrant Subscribe deadlocked")
	}
}

// recv pulls one message with a short timeout so a missed Publish fails
// the test instead of hanging.
func recv(t *testing.T, ch <-chan any) any {
	t.Helper()
	select {
	case v, ok := <-ch:
		require.True(t, ok, "channel closed unexpectedly")
		return v
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out waiting for message")
		return nil
	}
}
