package com.rafif.wallet.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs tasks on separate threads and releases them at the same instant, so they really compete for the
 * same rows. Each result is awaited with a timeout: a deadlock makes the test fail instead of hanging forever.
 */
public final class ConcurrentRunner {

    private static final long TIMEOUT_SECONDS = 60;

    private ConcurrentRunner() {
    }

    /** Runs every task once, each on its own thread, all at the same time. Returns the results in task order. */
    public static <T> List<T> runAll(List<Callable<T>> tasks) throws Exception {
        return runWithThreads(tasks.size(), tasks);
    }

    /**
     * Runs the tasks on a fixed number of threads: the threads start together, then pick tasks in list order.
     * Use this when there are many more tasks than threads (like a busy server). Results are in task order.
     */
    public static <T> List<T> runWithThreads(int threads, List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        // Only the first `threads` tasks can start before others finish, so wait for exactly that many.
        CountDownLatch ready = new CountDownLatch(Math.min(threads, tasks.size()));
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown(); // extra countDowns on an already-open latch are harmless
                    go.await();        // every thread waits here until all threads are up
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Runs the same task {@code times} times, all at the same time. */
    public static <T> List<T> runRepeated(int times, Callable<T> task) throws Exception {
        List<Callable<T>> tasks = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            tasks.add(task);
        }
        return runAll(tasks);
    }
}
