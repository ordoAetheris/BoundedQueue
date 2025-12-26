package com.ordoAetheris.drafts;

import com.ordoAetheris.drafts.task.BoundedWorkQueue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class BoundedBufferDemo {
        public static void main(String[] args) throws Exception {
            // =====================================================================
            // ЭМУЛЯЦИЯ ПРОДА: bounded очередь задач с backpressure.
            //
            // Сюжет:
            // - Producers: источник задач (HTTP/Rabbit/Kafka consumer → превращает в job)
            // - Queue(capacity): буфер "in-memory" с лимитом, чтобы не улететь в OOM
            // - Consumers: воркеры (пул), которые выполняют работу
            //
            // Ключевой эффект:
            // - если воркеры не успевают -> put() БЛОКИРУЕТСЯ (backpressure)
            // =====================================================================

            int capacity = 8;
            int producers = 2;
            int consumers = 2;
            int perProducer = 50;

            BoundedWorkQueue<Runnable> q = new BoundedWorkQueue<>(capacity);

            ExecutorService pool = Executors.newFixedThreadPool(producers + consumers);

            AtomicInteger produced = new AtomicInteger();
            AtomicInteger executed = new AtomicInteger();

            CountDownLatch start = new CountDownLatch(1);

            // ---------------------------------------------------------------------
            // Consumers: “воркеры” — берут job из очереди и выполняют.
            // Важно: выполнение job.run() происходит ВНЕ очереди и её лока.
            // ---------------------------------------------------------------------
            for (int i = 0; i < consumers; i++) {
                final int workerId = i;
                pool.submit(() -> {
                    await(start);
                    try {
                        while (true) {
                            Runnable job = q.take();
                            if (job == null) {
                                // EOF: очередь закрыта и пуста, воркер завершается
                                return;
                            }
                            // Эмулируем "полезную работу"
                            job.run();
                            executed.incrementAndGet();

                            // небольшой микроджиттер, чтобы перемешать планировщик
                            microJitter();
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            // ---------------------------------------------------------------------
            // Producers: “приём задач” — создают job и пытаются положить в очередь.
            // Если очередь заполнена — put() блокируется (backpressure).
            // ---------------------------------------------------------------------
            for (int p = 0; p < producers; p++) {
                final int producerId = p;
                pool.submit(() -> {
                    await(start);
                    try {
                        for (int i = 0; i < perProducer; i++) {
                            int jobId = producerId * perProducer + i;

                            Runnable job = () -> {
                                // "бизнес-операция": обработка конкретной задачи
                                // (в реальности тут была бы логика обработки события/запроса)
                                // Для демо — просто пустая работа.
                                cpuTinyWork();
                            };

                            Instant t0 = Instant.now();
                            q.put(job); // <-- здесь и проявляется backpressure
                            long blockedMicros = Duration.between(t0, Instant.now()).toNanos() / 1_000;

                            int n = produced.incrementAndGet();

                            if (blockedMicros > 200) {
                                System.out.printf("producer-%d: put blocked ~%dµs (queue likely full), totalProduced=%d%n",
                                        producerId, blockedMicros, n);
                            }

                            microJitter();
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            start.countDown();

            // ---------------------------------------------------------------------
            // Ждём, пока producers “закончат работу”, затем закрываем очередь.
            // В проде: это graceful shutdown — новые задачи не принимаем,
            // но уже принятые должны быть обработаны.
            // ---------------------------------------------------------------------
            pool.shutdown();
            boolean finished = pool.awaitTermination(10, TimeUnit.SECONDS);

            if (!finished) {
                System.out.println("Pool didn't finish in time; calling close() + shutdownNow()");
            }

            // Закрываем очередь: воркеры доберут остаток и завершатся на EOF.
            q.close();

            // В проде обычно: дождаться завершения воркеров; в демо — просто печать.
            System.out.printf("produced=%d executed=%d%n", produced.get(), executed.get());
        }

        private static void await(CountDownLatch latch) {
            try { latch.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }

        private static void microJitter() {
            // 1/64 вероятности — микропауза до 50µs (не миллисекунды!)
            // Это даёт interleavings, но не раздувает время демо/тестов.
            if ((ThreadLocalRandom.current().nextInt() & 63) != 0) return;
            LockSupport.parkNanos(ThreadLocalRandom.current().nextInt(50_000));
        }

        private static void cpuTinyWork() {
            // минимальная "CPU-работа" вместо sleep
            long x = 0;
            for (int i = 0; i < 200; i++) x = x * 31 + i;
            if (x == 42) System.out.print(""); // чтобы компилятор не выкинул
        }



}