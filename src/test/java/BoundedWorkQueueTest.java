import com.ordoAetheris.drafts.task.BoundedWorkQueue;
import org.junit.jupiter.api.*;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.BitSet;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

    @DisplayName("Bounded Buffer: BoundedWorkQueue<T>")
    class BoundedWorkQueueTest {

        // ---------------------------- FUNCTIONAL REQUIREMENTS ----------------------------

        @Nested
        @DisplayName("Functional requirements")
        class Functional {

            @Test
            @DisplayName("put(null) -> IllegalArgumentException")
            void putNull_throwsIAE() {
                BoundedWorkQueue<Integer> q = new BoundedWorkQueue<>(4);
                assertThrows(IllegalArgumentException.class, () -> q.put(null));
            }

            @Test
            @DisplayName("capacity must be > 0")
            void capacityMustBePositive() {
                assertThrows(IllegalArgumentException.class, () -> new BoundedWorkQueue<>(0));
                assertThrows(IllegalArgumentException.class, () -> new BoundedWorkQueue<>(-1));
            }

            @Test
            @DisplayName("put after close -> IllegalStateException")
            void putAfterClose_throwsISE() {
                BoundedWorkQueue<Integer> q = new BoundedWorkQueue<>(2);
                q.close();
                assertThrows(IllegalStateException.class, () -> q.put(1));
            }

            @Test
            @DisplayName("take on empty+closed -> null (EOF)")
            void takeEmptyClosed_returnsNull() throws Exception {
                BoundedWorkQueue<Integer> q = new BoundedWorkQueue<>(2);
                q.close();
                assertNull(q.take());
            }

            @Test
            @DisplayName("close is idempotent")
            void closeIsIdempotent() {
                BoundedWorkQueue<Integer> q = new BoundedWorkQueue<>(2);
                q.close();
                q.close();
                q.close();
            }

            @Test
            @DisplayName("close does not drop already enqueued items")
            void closeDoesNotDropAlreadyEnqueuedItems() throws Exception {
                BoundedWorkQueue<Integer> q = new BoundedWorkQueue<>(3);
                q.put(1);
                q.put(2);
                q.put(3);

                q.close(); // закрыли вход

                assertEquals(1, q.take());
                assertEquals(2, q.take());
                assertEquals(3, q.take());

                assertNull(q.take()); // EOF
            }

            @Test
            @DisplayName("put blocks on full until take makes space (backpressure)")
            void putBlocksWhenFull_untilTakeFreesSlot() throws Exception {
                BoundedWorkQueue<Integer> q = new BoundedWorkQueue<>(1);

                // заполняем очередь
                q.put(111);

                ExecutorService pool = Executors.newFixedThreadPool(1);
                CountDownLatch startedPut = new CountDownLatch(1);
                CountDownLatch allowTake = new CountDownLatch(1);

                Future<?> putter = pool.submit(() -> {
                    startedPut.countDown();
                    // должна блокироваться, пока основной поток не сделает take()
                    q.put(222);
                    return null;
                });

                startedPut.await();

                // даём putter шанс "упереться" в full
                microJitter();
                assertFalse(putter.isDone(), "put should block when queue is full");

                // теперь освобождаем место
                allowTake.countDown();
                Integer first = q.take();
                assertEquals(111, first);

                // putter должен завершиться быстро
                getOrDump(putter, 1, pool, "putBlocksWhenFull");

                // в очереди должен появиться второй элемент
                assertEquals(222, q.take());

                q.close();
                pool.shutdownNow();
            }

            @Test
            @DisplayName("take blocks on empty until put arrives")
            void takeBlocksWhenEmpty_untilPutArrives() throws Exception {
                BoundedWorkQueue<Integer> q = new BoundedWorkQueue<>(2);

                ExecutorService pool = Executors.newFixedThreadPool(1);
                CountDownLatch startedTake = new CountDownLatch(1);

                Future<Integer> taker = pool.submit(() -> {
                    startedTake.countDown();
                    return q.take(); // должна ждать
                });

                startedTake.await();
                microJitter();

                assertFalse(taker.isDone(), "take should block when queue is empty");

                q.put(7);
                Integer x = getOrDump(taker, 1, pool, "takeBlocksWhenEmpty");
                assertEquals(7, x);

                q.close();
                pool.shutdownNow();
            }

            @Test
            @DisplayName("close unblocks waiting take()")
            void closeUnblocksTake() throws Exception {
                BoundedWorkQueue<Integer> q = new BoundedWorkQueue<>(2);

                ExecutorService pool = Executors.newFixedThreadPool(1);
                CountDownLatch started = new CountDownLatch(1);

                Future<Integer> f = pool.submit(() -> {
                    started.countDown();
                    return q.take(); // ждём
                });

                started.await();
                microJitter();

                q.close(); // должен разбудить taker

                Integer res = getOrDump(f, 1, pool, "closeUnblocksTake");
                assertNull(res);

                pool.shutdownNow();
            }

            @Test
            @DisplayName("close unblocks waiting put() (when queue is full)")
            void closeUnblocksPut() throws Exception {
                BoundedWorkQueue<Integer> q = new BoundedWorkQueue<>(1);

                // делаем full
                q.put(1);

                ExecutorService pool = Executors.newFixedThreadPool(1);
                CountDownLatch started = new CountDownLatch(1);

                Future<?> f = pool.submit(() -> {
                    started.countDown();
                    // должно ждать из-за full, но close() обязан разбудить
                    q.put(2);
                    return null;
                });

                started.await();
                microJitter();
                assertFalse(f.isDone(), "put should be blocked on full queue");

                q.close();

                // после close() по спецификации put() должен завершиться исключением ISE
                ExecutionException ex = assertThrows(ExecutionException.class, () -> f.get(1, TimeUnit.SECONDS));
                assertTrue(ex.getCause() instanceof IllegalStateException,
                        "expected IllegalStateException from put() after close");

                pool.shutdownNow();
            }
        }

        // -------------------------- NON-FUNCTIONAL REQUIREMENTS --------------------------

        @Nested
        @DisplayName("Non-functional requirements (stress / race-hunting)")
        class NonFunctional {

            @Test
            @DisplayName("SPSC: no loss, no duplicates (with micro-jitter), 200 runs")
            void spsc_noLoss_noDup_manyRuns200() throws Exception {
                // SPSC = Single Producer / Single Consumer
                int runs = 200;
                int n = 50_000;
                int capacity = 64;

                for (int r = 0; r < runs; r++) {
                    BoundedWorkQueue<Integer> q = new BoundedWorkQueue<>(capacity);

                    BitSet seen = new BitSet(n);
                    AtomicInteger consumed = new AtomicInteger();

                    ExecutorService pool = Executors.newFixedThreadPool(2);
                    CountDownLatch start = new CountDownLatch(1);

                    Future<?> prod = pool.submit(() -> { await(start); call(() -> {
                        for (int i = 0; i < n; i++) {
                            microJitter();
                            q.put(i);
                        }
                        q.close();
                    });});

                    int finalR = r;
                    Future<?> cons = pool.submit(() -> { await(start); call(() -> {
                        while (true) {
                            microJitter();
                            Integer x = q.take();
                            if (x == null) break;

                            // BitSet не thread-safe, но у нас один consumer; synchronized оставим для шаблона
                            synchronized (seen) {
                                if (seen.get(x)) fail("duplicate item: " + x + ", run=" + finalR);
                                seen.set(x);
                            }
                            consumed.incrementAndGet();
                        }
                    });});

                    start.countDown();

                    try {
                        getOrDump(prod, 6, pool, "run=" + r + ":producer");
                        getOrDump(cons, 6, pool, "run=" + r + ":consumer");
                    } catch (AssertionError e) {
                        throw new AssertionError("Failed on run=" + r +
                                ", consumed=" + consumed.get() +
                                ", seen=" + seen.cardinality(), e);
                    } finally {
                        pool.shutdownNow();
                    }

                    assertEquals(n, consumed.get(), "run=" + r);
                    synchronized (seen) {
                        assertEquals(n, seen.cardinality(), "run=" + r);
                    }
                }
            }

            @Test
            @DisplayName("MPMC: multiple producers/consumers, no loss/no duplicates")
            void mpmc_smoke_noLoss_noDup() throws Exception {
                // MPMC = Multiple Producers / Multiple Consumers
                int producers = 4;
                int consumers = 4;
                int perProducer = 20_000;
                int total = producers * perProducer;

                BoundedWorkQueue<Integer> q = new BoundedWorkQueue<>(128);

                BitSet seen = new BitSet(total);
                AtomicInteger consumed = new AtomicInteger();

                ExecutorService pool = Executors.newFixedThreadPool(producers + consumers);
                CountDownLatch start = new CountDownLatch(1);

                Future<?>[] consF = new Future<?>[consumers];
                for (int c = 0; c < consumers; c++) {
                    consF[c] = pool.submit(() -> { await(start); call(() -> {
                        while (true) {
                            Integer x = q.take();
                            if (x == null) break;
                            synchronized (seen) {
                                if (seen.get(x)) fail("duplicate item: " + x);
                                seen.set(x);
                            }
                            consumed.incrementAndGet();
                            microJitter();
                        }
                    });});
                }

                Future<?>[] prodF = new Future<?>[producers];
                for (int p = 0; p < producers; p++) {
                    final int base = p * perProducer;
                    prodF[p] = pool.submit(() -> { await(start); call(() -> {
                        for (int i = 0; i < perProducer; i++) {
                            q.put(base + i);
                            microJitter();
                        }
                    });});
                }

                start.countDown();

                for (Future<?> f : prodF) getOrDump(f, 8, pool, "mpmc:producer");
                q.close();
                for (Future<?> f : consF) getOrDump(f, 8, pool, "mpmc:consumer");

                pool.shutdownNow();

                assertEquals(total, consumed.get());
                synchronized (seen) {
                    assertEquals(total, seen.cardinality());
                }
            }
        }

        // ------------------------------- helpers (shared) -------------------------------

        private static void microJitter() {
            // 1/64 chance, up to 50µs
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            if ((rnd.nextInt() & 63) != 0) return;
            LockSupport.parkNanos(rnd.nextInt(50_000));
        }

        private static <T> T getOrDump(Future<T> f, int sec, ExecutorService pool, String tag) throws Exception {
            try {
                return f.get(sec, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                System.err.println("=== TIMEOUT [" + tag + "] thread dump ===");
                dumpThreads();
                pool.shutdownNow();
                throw new AssertionError("Timeout: " + tag, e);
            }
        }

        private static void dumpThreads() {
            ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            ThreadInfo[] infos = mx.dumpAllThreads(true, true);
            for (ThreadInfo ti : infos) {
                System.err.println(ti.toString());
            }
            long[] dead = mx.findDeadlockedThreads();
            if (dead != null && dead.length > 0) {
                System.err.println("=== DEADLOCK DETECTED ===");
                ThreadInfo[] di = mx.getThreadInfo(dead, true, true);
                for (ThreadInfo ti : di) System.err.println(ti.toString());
            }
        }

        private static void await(CountDownLatch latch) {
            try { latch.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }

        private static void call(ThrowingRunnable r) {
            try { r.run(); } catch (Exception e) { throw new RuntimeException(e); }
        }

        @FunctionalInterface
        interface ThrowingRunnable {
            void run() throws Exception;
        }
    }

