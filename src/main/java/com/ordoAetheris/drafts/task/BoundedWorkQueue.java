package com.ordoAetheris.drafts.task;
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
public class BoundedWorkQueue <T>{

    public BoundedWorkQueue(int capacity) {}

    public void put(T element) throws InterruptedException {}
    public T take() throws InterruptedException {}
    public void close(){};
}
