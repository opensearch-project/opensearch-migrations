// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package assistant

import (
	"strings"
)

// SmartRouter provides intelligent routing decisions for queries
type SmartRouter struct{}

// NewSmartRouter creates a new smart router instance
func NewSmartRouter() *SmartRouter {
	return &SmartRouter{}
}

// ShouldUseLLM determines if a query should use LLM enhancement
func (r *SmartRouter) ShouldUseLLM(query string, session *ConversationSession, nlpConfidence float64) bool {
	// Always use LLM for first message in a conversation
	if len(session.Messages) == 0 {
		return true
	}

	// Use LLM for conversational queries (follow-ups, modifications)
	if r.IsConversational(query) {
		return true
	}

	// Use LLM for ambiguous queries (low NLP confidence)
	if nlpConfidence < 0.80 {
		return true
	}

	// Use NLP for high-confidence explicit queries
	if nlpConfidence >= 0.90 {
		return false
	}

	// Default: use LLM for everything in between
	return true
}

// IsConversational detects if a query is part of an ongoing conversation
func (r *SmartRouter) IsConversational(query string) bool {
	queryLower := strings.ToLower(query)

	// Follow-up indicators
	followUpPhrases := []string{
		"what if", "what about", "how about", "try",
		"instead", "also", "additionally", "another",
		"change", "modify", "update", "increase", "decrease",
		"same but", "similar but", "like that but",
		"can you", "could you", "please",
	}

	for _, phrase := range followUpPhrases {
		if strings.Contains(queryLower, phrase) {
			return true
		}
	}

	// Reference indicators (referring to previous context)
	referenceWords := []string{
		"that", "this", "it", "them", "those", "these",
		"the cluster", "the same", "the config",
	}

	for _, word := range referenceWords {
		if strings.Contains(queryLower, word) {
			return true
		}
	}

	// Question words that often indicate follow-ups
	questionStarters := []string{
		"and ", "or ", "but ",
	}

	for _, starter := range questionStarters {
		if strings.HasPrefix(queryLower, starter) {
			return true
		}
	}

	return false
}

// GetConfidenceThreshold returns the appropriate confidence threshold based on context
func (r *SmartRouter) GetConfidenceThreshold(session *ConversationSession) float64 {
	// First message: higher threshold (more selective about LLM usage)
	if len(session.Messages) == 0 {
		return 0.90
	}

	// Follow-up messages: lower threshold (more likely to use LLM for context)
	return 0.80
}

// IsAmbiguous detects if a query is ambiguous and needs LLM clarification
func (r *SmartRouter) IsAmbiguous(query string) bool {
	queryLower := strings.ToLower(query)

	// Vague sizing
	vagueTerms := []string{
		"small", "medium", "large",
		"a lot", "some", "few",
		"many", "several",
	}

	for _, term := range vagueTerms {
		if strings.Contains(queryLower, term) {
			return true
		}
	}

	// Missing critical information (very short queries)
	if len(query) < 20 {
		return true
	}

	// Questions without specifics
	openQuestions := []string{
		"how much", "what's the cost", "price",
		"estimate", "quote",
	}

	for _, question := range openQuestions {
		if strings.Contains(queryLower, question) && !strings.Contains(queryLower, "vector") && !strings.Contains(queryLower, "search") && !strings.Contains(queryLower, "log") {
			return true
		}
	}

	return false
}

// ShouldUseConversationContext determines if conversation history should be included
func (r *SmartRouter) ShouldUseConversationContext(session *ConversationSession) bool {
	// No context needed for first message
	if len(session.Messages) == 0 {
		return false
	}

	// Use context if we have recent messages
	if len(session.Messages) > 0 {
		return true
	}

	return false
}

// GetRecentMessageCount returns the number of recent messages to include as context
func (r *SmartRouter) GetRecentMessageCount(session *ConversationSession) int {
	messageCount := len(session.Messages)

	// Limit context window to avoid token overflow
	if messageCount > 10 {
		return 10 // Last 10 messages (5 exchanges)
	}

	return messageCount
}
