package jp.tekewo.conversation;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class MemoryWorkerTest {
    public static void main(String[] arguments) throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicInteger queued = new AtomicInteger();
        try (MemoryWorker worker = new MemoryWorker()) {
            worker.execute(() -> {
                started.countDown();
                try { assert release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            assert started.await(5, TimeUnit.SECONDS);
            assert worker.isRunning();
            for (int i = 0; i < 100; i++) worker.execute(() -> { queued.incrementAndGet(); done.countDown(); });
            release.countDown();
            assert done.await(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assert queued.get() == 1 : "Rapid sends must not queue duplicate memory scans";

        ChatSessionTest.Store store = new ChatSessionTest.Store();
        ChatSession replies = new ChatSession(store), memories = new ChatSession(store);
        replies.reply("最初の会話", true, (instructions, input, delta) -> "最初の返答", new ChatSessionTest.Listener());
        CountDownLatch extracting = new CountDownLatch(1), continueMemory = new CountDownLatch(1), saved = new CountDownLatch(1);
        try (MemoryWorker worker = new MemoryWorker()) {
            worker.execute(() -> {
                try {
                    memories.processMemories((instructions, input, delta) -> {
                        extracting.countDown();
                        if (!continueMemory.await(5, TimeUnit.SECONDS)) throw new AssertionError("Memory test timed out");
                        return "{\"memories\":[]}";
                    });
                    saved.countDown();
                } catch (Exception error) { throw new AssertionError(error); }
            });
            assert extracting.await(5, TimeUnit.SECONDS);
            replies.reply("記憶整理中の次の会話", true, (instructions, input, delta) -> "待たずに返答", new ChatSessionTest.Listener());
            assert store.texts.get(3).equals("待たずに返答") && store.applied == 0;
            replies.cancel();
            assert !memories.isCancelled() : "Stopping a reply must not share the memory session's cancellation flag";
            continueMemory.countDown();
            assert saved.await(5, TimeUnit.SECONDS);
        } finally { continueMemory.countDown(); }
        assert store.applied == 1;
        System.out.println("PASS: memory coalescing, sequential updates and replies while extraction is blocked");
    }
}
