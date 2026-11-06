package com.aistock.research.integration.eastmoney;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(10)
class EastMoneyRequestThrottleTest {

    private final EastMoneyClient client = new EastMoneyClient(null, new ObjectMapper(), null);

    @Test
    void cancellingAQueuedRequestExitsBeforeTheCurrentRateLimitWaitFinishes() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptedAtExit = new AtomicBoolean();
        CountDownLatch completed = new CountDownLatch(1);
        Thread holder = waitingHolder();
        Thread waiter = worker("cancelled-eastmoney-request", () -> {
            try {
                throttle();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                interruptedAtExit.set(Thread.currentThread().isInterrupted());
                completed.countDown();
            }
        });
        try {
            holder.start();
            awaitState(holder, Thread.State.TIMED_WAITING);
            waiter.start();
            awaitState(waiter, Thread.State.BLOCKED, Thread.State.WAITING);

            waiter.interrupt();

            assertThat(completed.await(500, TimeUnit.MILLISECONDS))
                    .as("cancellation must not wait for another request to release the limiter").isTrue();
            assertThat(failure.get()).isInstanceOf(CancellationException.class);
            assertThat(interruptedAtExit.get()).isTrue();
            assertThat(holder.isAlive()).isTrue();
        } finally {
            stopWorkers(List.of(holder, waiter));
        }
    }

    @Test
    void queuedRequestsProceedInArrivalOrderWithoutIncreasingTheRequestRate() throws Exception {
        List<Integer> order = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(3);
        Thread holder = waitingHolder();
        List<Thread> workers = new ArrayList<>(List.of(holder));
        try {
            holder.start();
            awaitState(holder, Thread.State.TIMED_WAITING);
            for (int index = 0; index < 3; index++) {
                int position = index;
                Thread waiter = worker("queued-eastmoney-request-" + index, () -> {
                    try {
                        throttle();
                        order.add(position);
                    } catch (Throwable error) {
                        failures.add(error);
                    } finally {
                        completed.countDown();
                    }
                });
                workers.add(waiter);
                waiter.start();
                awaitState(waiter, Thread.State.BLOCKED, Thread.State.WAITING);
            }
            // End the fixture's long wait only after all three requests are known to be queued.
            setLastRequestMillis(0);
            long releasedAt = System.nanoTime();
            holder.interrupt();

            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(failures).isEmpty();
            assertThat(order).containsExactly(0, 1, 2);
            assertThat(System.nanoTime() - releasedAt)
                    .as("three queued requests must retain two 650ms source intervals")
                    .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(1280));
        } finally {
            stopWorkers(workers);
        }
    }

    private Thread waitingHolder() throws Exception {
        // Keep the lock occupied deterministically; this fixture changes no production rate setting.
        setLastRequestMillis(System.currentTimeMillis() + 10_000);
        return worker("eastmoney-current-request", () -> {
            try {
                throttle();
            } catch (CancellationException expectedDuringCleanup) {
                // The tests interrupt this current request to release the waiting queue.
            }
        });
    }

    private Thread worker(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private void throttle() {
        try {
            var method = EastMoneyClient.class.getDeclaredMethod("throttleRemote", String.class);
            method.setAccessible(true);
            method.invoke(client, "https://push2.eastmoney.com/api/qt/clist/get");
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private void setLastRequestMillis(long value) throws Exception {
        var field = EastMoneyClient.class.getDeclaredField("lastEastMoneyRequestMillis");
        field.setAccessible(true);
        field.setLong(client, value);
    }

    private void awaitState(Thread thread, Thread.State... states) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (List.of(states).contains(thread.getState())) return;
            Thread.sleep(5);
        }
        assertThat(thread.getState()).isIn((Object[]) states);
    }

    private void stopWorkers(List<Thread> workers) throws Exception {
        setLastRequestMillis(0);
        workers.forEach(Thread::interrupt);
        for (Thread thread : workers) {
            thread.join(2_000);
            assertThat(thread.isAlive()).as(thread.getName() + " must terminate").isFalse();
        }
    }
}
