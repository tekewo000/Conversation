package jp.tekewo.conversation;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One memory update at a time; repeated requests keep only the next update. */
final class MemoryWorker implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "Conversation memory"); thread.setDaemon(true); return thread;
    });
    private Runnable pending;
    private boolean running, closed;

    synchronized void execute(Runnable task) {
        if (closed) return;
        pending = task;
        if (!running) { running = true; executor.execute(this::drain); }
    }

    synchronized boolean isRunning() { return running && !closed; }

    private void drain() {
        try {
            while (true) {
                Runnable task;
                synchronized (this) {
                    if (closed || pending == null) return;
                    task = pending; pending = null;
                }
                task.run();
            }
        } finally {
            synchronized (this) {
                running = false;
                if (!closed && pending != null) { running = true; executor.execute(this::drain); }
            }
        }
    }

    @Override public synchronized void close() {
        closed = true; pending = null; executor.shutdownNow();
    }
}
