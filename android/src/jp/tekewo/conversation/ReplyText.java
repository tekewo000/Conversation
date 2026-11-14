package jp.tekewo.conversation;

import java.util.regex.Pattern;

/** Removes internal labels and the unsolicited prefix reported in chat, without filtering a language. */
final class ReplyText {
    static final String STATE_MARKER = "<assistant_state>";
    private static final Pattern LABELS = Pattern.compile("(?m)^[\\t ]*(?:\\[発言ID[\\t ]*[:：][\\t ]*\\d+\\]|\\[発言の記録時刻[\\t ]*[:：][^\\]\\r\\n]+\\]|L'd:[\\t ]*\\d{4}-\\d{2}-\\d{2}T[^\\]\\r\\n]+\\]|av不卡免费播放[\\t ]*(?:t:)?)[\\t ]*(?:\\r?\\n|$)");
    private static final Pattern CONTROL = Pattern.compile("<\\|[^<>\\r\\n]{1,80}\\|>");
    private static final String[] PREFIXES = {"[発言ID", "[発言の記録時刻", "L'd:", "av不卡免费播放"};

    private static final Pattern SPAM = Pattern.compile("(?m)^[\\t ]*" + Pattern.quote(PREFIXES[3]) + "[\\t ]*(?:t:)?[\\t ]*");

    static String visible(String value, boolean complete) {
        int state = value.indexOf(STATE_MARKER);
        if (state >= 0) value = value.substring(0, state).replaceFirst("\\r?\\n$", "");
        else if (!complete) {
            for (int length = Math.min(value.length(), STATE_MARKER.length() - 1); length > 0; length--) {
                if (value.endsWith(STATE_MARKER.substring(0, length))) {
                    value = value.substring(0, value.length() - length).replaceFirst("\\r?\\n$", "");
                    break;
                }
            }
        }
        String text = CONTROL.matcher(SPAM.matcher(LABELS.matcher(value).replaceAll("")).replaceAll("")).replaceAll("");
        int start = text.lastIndexOf('\n') + 1;
        String last = text.substring(start).trim();
        if (!complete && last.equals("t") && value.substring(value.lastIndexOf('\n') + 1).trim().startsWith(PREFIXES[3])) return text.substring(0, start);
        for (String prefix : PREFIXES)
            if (last.startsWith(prefix) || (!complete && prefix.startsWith(last))) return text.substring(0, start);
        int token = text.lastIndexOf("<|");
        if (token >= 0 && text.indexOf("|>", token) < 0) return text.substring(0, token);
        if (!complete && text.endsWith("<")) return text.substring(0, text.length() - 1);
        return text;
    }
}
