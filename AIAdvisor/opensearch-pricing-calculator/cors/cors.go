// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package cors

import (
	"os"
	"strings"
)

// AllowedOriginSuffixes contains domain suffixes that are allowed for CORS.
// Any HTTPS origin ending with these suffixes will be permitted.
var AllowedOriginSuffixes = []string{
	".aws.dev",       // All AWS dev subdomains
	".amazonaws.com", // AWS services
}

// AllowedOrigins contains specific origins allowed (populated at init).
var AllowedOrigins = initAllowedOrigins()

func initAllowedOrigins() map[string]bool {
	origins := map[string]bool{
		"http://localhost:3000": true, // Frontend dev server
		"http://localhost:3002": true, // Alternate frontend port
		"http://localhost:5050": true, // Backend HTTP server
		"http://localhost:8081": true, // MCP server
	}

	// Allow additional origins from environment variable
	if envOrigins := os.Getenv("ALLOWED_ORIGINS"); envOrigins != "" {
		for _, origin := range strings.Split(envOrigins, ",") {
			origins[strings.TrimSpace(origin)] = true
		}
	}

	return origins
}

// IsOriginAllowed checks if the given origin is allowed.
// It allows:
// 1. Exact matches from the AllowedOrigins map
// 2. Any HTTPS origin ending with an allowed suffix (e.g., *.aws.dev)
func IsOriginAllowed(origin string) bool {
	if AllowedOrigins[origin] {
		return true
	}

	for _, suffix := range AllowedOriginSuffixes {
		if strings.HasSuffix(origin, suffix) {
			if strings.HasPrefix(origin, "https://") {
				return true
			}
		}
	}

	return false
}
