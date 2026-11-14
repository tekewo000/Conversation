package jp.tekewo.conversation;

public final class ReplyTextTest {
    public static void main(String[] arguments) {
        String prefix = "av不卡免费播放 t:";
        String reply = prefix + "\nえっ、大丈夫？笑";
        for (int i = 1; i <= prefix.length(); i++)
            assert ReplyText.visible(reply.substring(0, i), false).isEmpty() : "Prefix leaked before its line completed";
        assert ReplyText.visible(prefix + "えっ、大丈夫？笑", true).equals("えっ、大丈夫？笑");
        assert ReplyText.visible(reply, true).equals("えっ、大丈夫？笑");
        assert ReplyText.visible("中国語の引用「你好」と、2026-11-14の予定。", true).equals("中国語の引用「你好」と、2026-11-14の予定。");
        String control = "<" + "|im_end|>";
        assert ReplyText.visible("話そう。" + control, true).equals("話そう。");
        assert ReplyText.visible("話そう。<|im_", false).equals("話そう。");
        assert ReplyText.visible("話そう。<|im_", true).equals("話そう。") : "Saving must not reveal an incomplete control token";
        assert ReplyText.visible("話そう。\n[発言ID:", true).equals("話そう。\n") : "Saving must not reveal an incomplete metadata label";
        assert ReplyText.visible("L", true).equals("L") : "Ordinary names must not be treated as incomplete labels";
        assert ReplyText.visible("[発言ID: 1385]\n本文\n[発言の記録時刻: 2026-11-14T03:51:11.093Z]", true).equals("本文\n");
        System.out.println("PASS: split internal labels/control tokens, reported unsolicited prefix and ordinary multilingual text");
    }
}
