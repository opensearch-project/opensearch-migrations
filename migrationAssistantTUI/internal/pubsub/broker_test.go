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
// One subscriber that never reads must not stall a publisher that is
// hammering messages. The slow channel's messages are dropped (counted
// by DropCount), and the fast channel still receives every event.
//
// Without this, a hung render goroutine would deadlock the deploy
// goroutine and the UI would freeze with no diagnostic.
func TestBroker_SlowConsumerDoesNotBlockFast(t *testing.T) {
	b := pubsub.NewBroker()
	t.Cleanup(b.Close)

	slow, _ := b.Subscribe() // intentionally never read
	fast, _ := b.Subscribe()

	const N = 100
	var fastReceived atomic.Int64
	done := make(chan struct{})
	go func() {
		for i := 0; i < N; i++ {
			<-fast
			fastReceived.Add(1)
		}
		close(done)
	}()

	for i := 0; i < N; i++ {
		b.Publish(i)
	}

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatalf("fast consumer stalled at %d/%d (slow blocking publisher?)",
			fastReceived.Load(), N)
	}

	// Slow channel had a buffer's worth, then dropped the rest.
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
