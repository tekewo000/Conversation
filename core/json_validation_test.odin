package conversation

import "core:testing"

@(test)
snapshot_rejects_lossy_or_malformed_tokens :: proc(t: ^testing.T) {
	bad := []string{
		`{"version":01,"messages":["ok"],"memories":[]}`,
		`{"version":18446744073709551617,"messages":[],"memories":[]}`,
		"{\"version\":1,\"messages\":[],\"memories\":[]}\x00garbage",
		`{"version":1,"messages":[],"memories":[],"":true}`,
		`{"version":1,"messages":["\ud800"],"memories":[]}`,
		`{"version":1,"messages":["\udc00"],"memories":[]}`,
	}
	for text in bad do testing.expect(t, !snapshot_tokens_valid(transmute([]byte)text))
	good := []string{
		`{"version":1,"messages":["こんにちは🎾"],"memories":[0]}`,
		`{"version":1,"messages":["\ud83c\udfbe"],"memories":[]}`,
		`{"version":1,"messages":["\\ud800 is literal text",""],"memories":[]}`,
	}
	for text in good do testing.expect(t, snapshot_tokens_valid(transmute([]byte)text))
}
