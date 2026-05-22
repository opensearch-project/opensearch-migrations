package marelease

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// ---------------------------------------------------------------------
// HTTPFetcher — production wiring around net/http. Tests use a fake;
// cmd/tui wires this. Lives in the same package so callers don't have
// to write the 404→ErrNotFound translation themselves and risk
// inconsistency.
// ---------------------------------------------------------------------

// HTTPFetcher implements Fetcher over net/http with a small set of
// hardened defaults: timeout, status-code routing, response-size cap.
//
// On non-2xx responses, returns:
//   - ErrNotFound for 404
//   - a wrapped error containing the status code for everything else
//
// Body size is capped at MaxBytes to bound memory if a misconfigured
// release serves a giant blob.
type HTTPFetcher struct {
	Client   *http.Client
	MaxBytes int64 // 0 = use default (256 MiB; helm charts stay well under).
}

// NewHTTPFetcher returns an HTTPFetcher with sensible defaults: 60s
// per-request timeout, follows redirects, 256 MiB cap.
func NewHTTPFetcher() *HTTPFetcher {
	return &HTTPFetcher{
		Client:   &http.Client{Timeout: 60 * time.Second},
		MaxBytes: 256 << 20,
	}
}

// Get implements Fetcher.
func (h *HTTPFetcher) Get(ctx context.Context, url string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("marelease: new request: %w", err)
	}
	// User-Agent helps GitHub differentiate this client in audit logs;
	// also sidesteps the rare backend that 403s on empty UA.
	req.Header.Set("User-Agent", "opensearch-migration-assistant-tui")
	req.Header.Set("Accept", "application/octet-stream, */*")

	resp, err := h.Client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("marelease: do request %s: %w", url, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return nil, ErrNotFound
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		// Read a snippet of the body to put in the error.
		snip, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
		return nil, fmt.Errorf("marelease: %s returned %d: %s",
			url, resp.StatusCode, strings.TrimSpace(string(snip)))
	}

	max := h.MaxBytes
	if max == 0 {
		max = 256 << 20
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, max+1))
	if err != nil {
		return nil, fmt.Errorf("marelease: read %s: %w", url, err)
	}
	if int64(len(body)) > max {
		return nil, fmt.Errorf("marelease: %s body exceeds cap (%d bytes)", url, max)
	}
	return body, nil
}

// Compile-time assertion.
var _ Fetcher = (*HTTPFetcher)(nil)

// (errors imported only when MaxBytes overflow path expands; keep it
// referenced to satisfy goimports if a future edit drops the use.)
var _ = errors.New
