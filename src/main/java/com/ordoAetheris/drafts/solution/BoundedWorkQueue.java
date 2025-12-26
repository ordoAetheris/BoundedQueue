package com.ordoAetheris.drafts.solution;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 Bounded Buffer (bounded WorkQueue) с close/cancel
 Что это за класс задач
 Bounded Buffer — это Producer–Consumer, но с ограничением размера буфера capacity.

 Главная идея: backpressure
 Если producers производят быстрее, чем consumers потребляют — система не должна:
 бесконечно расти в памяти
 уходить в OOM
 накапливать “долги”
 Вместо этого put() блокируется, пока не освободится место.

 Прод-аналоги (чтобы не мастерить велосипед)
 ArrayBlockingQueue (bounded, один lock)
 LinkedBlockingQueue(capacity) (bounded, но два lock’а)
 ThreadPoolExecutor + bounded queue (классика backpressure в JVM)
 “inflight limit” / “semaphore based backpressure” (часто в RPC/HTTP)


 Класс: BoundedWorkQueue<T>

 Разрешено: только НЕ-потокобезопасные коллекции (ArrayDeque), ReentrantLock, Condition.
 Методы
 void put(T item) throws InterruptedException
 item == null → IllegalArgumentException
 если closed → IllegalStateException
 если очередь заполнена и не closed → ждёт, пока появится место
 когда кладёт элемент → будит ожидающих take()

 T take() throws InterruptedException
 если очередь пуста и не closed → ждёт
 если очередь пуста и closed → возвращает null (EOF)
 иначе возвращает элемент и будит ожидающих put()

 void close()
 делает closed = true
 будит всех, кто ждёт на put() и take()
 close() idempotent

 Инварианты

 no lost items
 no duplicate items
 put() не должен проходить после close()
 после close() никто не висит “вечно”
 размер очереди 0..capacity
 */
//public class BoundedWorkQueue <T>{
//
//    private final Queue<T> queue;
//    private boolean closed = false;
//    private final int capacity;
//    private int size = 0;
//
//    private final ReentrantLock lock = new ReentrantLock();
//    private final Condition notEmpty = lock.newCondition();
//    private final Condition notFull = lock.newCondition();
//
//    public BoundedWorkQueue(int capacity) {
//        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
//        queue = new ArrayDeque<>(capacity);
//        this.capacity = capacity;
//    }
//    public void put(T element) throws InterruptedException {
//        if (element == null) throw new IllegalArgumentException();
//        lock.lock();
//        try {
//            while(!closed && size >= capacity) notFull.await();
//            if (closed) throw new IllegalStateException();
//            queue.offer(element);
//            size++;
//            notEmpty.signal();
//        } finally {
//            lock.unlock();
//        }
//
//    }
//    public T take() throws InterruptedException {
//        lock.lock();
//        try {
//            while (!closed && queue.isEmpty()) notEmpty.await();
//            if (closed && queue.isEmpty()) return null;
//            T result = queue.poll();
//            size--;
//            notFull.signal();
//            return result;
//        } finally {
//            lock.unlock();
//        }
//    }
//    public void close(){
//        lock.lock();
//        try {
//            closed = true;
//            notEmpty.signalAll();
//            notFull.signalAll();
//        } finally {
//            lock.unlock();
//        }
//    }
//
//}
