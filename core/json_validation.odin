package conversation

import "core:encoding/json"
import "core:strconv"
import "core:strings"
import "core:unicode/utf8"

hex_quad :: proc(text: string) -> (value: u32, ok: bool) {
	if len(text) != 4 do return 0, false
	for character in text {
		value *= 16
		switch {
		case character >= '0' && character <= '9': value += u32(character - '0')
		case character >= 'a' && character <= 'f': value += u32(character - 'a' + 10)
		case character >= 'A' && character <= 'F': value += u32(character - 'A' + 10)
		case: return 0, false
		}
	}
	return value, true
}

paired_surrogates :: proc(text: string) -> bool {
	for i := 0; i < len(text); i += 1 {
		if text[i] != '\\' do continue
		i += 1
		if i >= len(text) do return false
		if text[i] != 'u' do continue
		if i + 5 > len(text) do return false
		code, valid := hex_quad(text[i + 1:i + 5])
		if !valid || (code >= 0xdc00 && code <= 0xdfff) do return false
		i += 4
		if code >= 0xd800 && code <= 0xdbff {
			if i + 7 > len(text) || text[i + 1:i + 3] != "\\u" do return false
			low, low_valid := hex_quad(text[i + 3:i + 7])
			if !low_valid || low < 0xdc00 || low > 0xdfff do return false
			i += 6
		}
	}
	return true
}

snapshot_tokens_valid :: proc(bytes: []byte) -> bool {
	data := string(bytes)
	if !utf8.valid_string(data) || strings.contains_rune(data, 0) do return false
	tokenizer := json.make_tokenizer(data, .JSON, true)
	previous: json.Token
	depth := 0
	for {
		token, token_error := json.get_token(&tokenizer)
		if token.kind == .EOF do return token_error == .EOF && depth == 0
		// The library's structural validator does not propagate every token error.
		if token_error != nil do return false
		#partial switch token.kind {
		case .Integer:
			_, valid := strconv.parse_i64(token.text, 10)
			if !valid do return false
		case .String:
			if !paired_surrogates(token.text) do return false
		case .Colon:
			if previous.kind == .String && previous.text == `""` do return false
		case .Open_Brace, .Open_Bracket:
			depth += 1
			if depth > 2 do return false
		case .Close_Brace, .Close_Bracket:
			depth -= 1
			if depth < 0 do return false
		}
		previous = token
	}
}
