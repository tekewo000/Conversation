package jp.tekewo.conversation;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public final class HttpRequestPoolTest {
    static final class Connection extends HttpURLConnection {
        boolean disconnected;
        Connection() throws Exception { super(new URL("https://example.invalid/")); }
        public void connect() {}
        public boolean usingProxy() { return false; }
        public void disconnect() { disconnected = true; }
    }
    public static void main(String[] arguments) throws Exception {
        HttpRequestPool pool = new HttpRequestPool();
        int attempt = pool.generation();
        Connection reply = new Connection(), memory = new Connection();
        pool.register(reply, attempt); pool.register(memory, attempt);
        pool.finish(reply);
        assert reply.disconnected && !memory.disconnected;
        pool.cancel();
        assert memory.disconnected && !pool.isCurrent(attempt);
        Connection stale = new Connection();
        try { pool.register(stale, attempt); throw new AssertionError("Cancelled request was registered"); }
        catch (IOException expected) { pool.finish(stale); }
        assert stale.disconnected;
        Connection next = new Connection();
        pool.register(next, pool.generation());
        pool.cancel();
        assert next.disconnected;
        HttpRequestPool all = new HttpRequestPool(), foreground = new HttpRequestPool();
        Connection chat = new Connection(), extraction = new Connection();
        all.register(chat, all.generation()); all.register(extraction, all.generation());
        foreground.register(chat, foreground.generation());
        foreground.cancel();
        assert chat.disconnected && !extraction.disconnected : "Reply cancellation must leave memory HTTP running";
        all.cancel();
        assert extraction.disconnected;
        System.out.println("PASS: simultaneous HTTP request tracking, cancellation and stale registration rejection");
    }
}
