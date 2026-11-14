package jp.tekewo.conversation;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.HashSet;
import java.util.Set;

/** Tracks simultaneous reply and memory requests so cancel cannot leave one running. */
final class HttpRequestPool {
    private final Set<HttpURLConnection> active = new HashSet<>();
    private int generation;

    synchronized int generation() { return generation; }
    synchronized boolean isCurrent(int attempt) { return attempt == generation; }

    synchronized void register(HttpURLConnection connection, int attempt) throws IOException {
        if (attempt != generation || Thread.currentThread().isInterrupted()) throw new IOException("処理をキャンセルしました。");
        active.add(connection);
    }

    void finish(HttpURLConnection connection) {
        if (connection == null) return;
        synchronized (this) { active.remove(connection); }
        connection.disconnect();
    }

    void cancel() {
        HttpURLConnection[] connections;
        synchronized (this) {
            generation++;
            connections = active.toArray(new HttpURLConnection[0]);
            active.clear();
        }
        for (HttpURLConnection connection : connections) connection.disconnect();
    }
}
