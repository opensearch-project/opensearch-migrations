package pubsub

import (
	"sync"
	"sync/atomic"
)

// defaultBufSize is the per-subscriber channel buffer. Empirically chosen:
//   - large enough that bursty deploy events (10-15 phase updates in a
//     few ms) never drop on a healthy consumer.
//   - small enough that a hung consumer is detected quickly via
//     DropCount in the file logger.
const defaultBufSize = 64

// Broker is the fan-out hub for backend → UI messages.
//
// The zero value is NOT usable; construct via NewBroker.
type Broker struct {
	mu      sync.RWMutex
	subs    map[*subscription]struct{}
	closed  bool
	dropped atomic.Int64
}

type subscription struct {
	ch     chan any
	once   sync.Once // guards close(ch) so unsub is idempotent.
	closed atomic.Bool
}

// NewBroker constructs an empty broker.
func NewBroker() *Broker {
	return &Broker{
		subs: make(map[*subscription]struct{}),
	}
}

// Subscribe returns a receive-only channel that yields published messages
// and an unsub function that closes the channel and removes the
// subscription. Both are safe to call from any goroutine; unsub is
// idempotent.
//
// After Broker.Close, Subscribe returns an already-closed channel and a
// no-op unsub. This avoids special-casing in callers (tea.Sub teardown
// often races with broker close).
func (b *Broker) Subscribe() (<-chan any, func()) {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.closed {
		ch := make(chan any)
		close(ch)
		return ch, func() {}
	}

	s := &subscription{
		ch: make(chan any, defaultBufSize),
	}
	b.subs[s] = struct{}{}

	unsub := func() {
		b.removeSubscription(s)
	}
	return s.ch, unsub
}

// Publish fans the message out to every active subscriber.
//
// Slow-consumer policy: if a subscriber's channel is full, the message
// is dropped for that subscriber and DropCount is incremented. The
// publishing goroutine is NEVER blocked by a slow consumer. This is
// load-bearing: the deploy goroutine cannot afford to stall waiting for
// a hung renderer.
func (b *Broker) Publish(msg any) {
	// RLock so concurrent publishers fan out in parallel and re-entrant
	// Subscribe (a subscriber's goroutine calling Subscribe in response
	// to a message) doesn't deadlock — Subscribe takes a write lock,
	// which is granted after all current RLocks release.
	b.mu.RLock()
	if b.closed {
		b.mu.RUnlock()
		return
	}
	// Snapshot subscribers under the read lock so we can release it before
	// trying to send. Sending into a buffered channel that's full would
	// otherwise hold the lock and block subsequent Subscribe/Publish.
	snapshot := make([]*subscription, 0, len(b.subs))
	for s := range b.subs {
		snapshot = append(snapshot, s)
	}
	b.mu.RUnlock()

	for _, s := range snapshot {
		if s.closed.Load() {
			continue
		}
		select {
		case s.ch <- msg:
			// delivered
		default:
			b.dropped.Add(1)
		}
	}
}

// DropCount returns the number of messages that have been dropped due
// to slow consumers since broker creation. Exposed primarily so tests
// and the file logger can detect a stuck UI.
func (b *Broker) DropCount() int64 {
	return b.dropped.Load()
}

// Close terminates the broker. Every active subscription's channel is
// closed; subsequent Publish calls are no-ops; subsequent Close calls
// are no-ops; subsequent Subscribe calls return an already-closed
// channel. Close is safe to call from any goroutine.
func (b *Broker) Close() {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.closed {
		return
	}
	b.closed = true

	for s := range b.subs {
		s.once.Do(func() {
			s.closed.Store(true)
			close(s.ch)
		})
	}
	b.subs = nil
}

// removeSubscription is the unsub closure body. Idempotent.
func (b *Broker) removeSubscription(s *subscription) {
	b.mu.Lock()
	defer b.mu.Unlock()

	if _, ok := b.subs[s]; !ok {
		// Already removed (Close, prior unsub call, or broker never owned
		// this subscription). once.Do still guards close-of-channel below.
	} else {
		delete(b.subs, s)
	}

	s.once.Do(func() {
		s.closed.Store(true)
		close(s.ch)
	})
}
