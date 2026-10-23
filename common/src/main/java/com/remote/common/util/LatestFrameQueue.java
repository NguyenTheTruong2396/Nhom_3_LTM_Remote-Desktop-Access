package com.remote.common.util;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hàng đợi Latest-Frame Queue (Drop Frame)
 * Chỉ giữ duy nhất 1 Frame mới nhất. Tự động loại bỏ (drop) các frame cũ chưa kịp gửi
 * để triệt tiêu hoàn toàn lag tích tụ khi mạng bị nghẽn.
 */
public class LatestFrameQueue<T> {
    private final AtomicReference<T> latestFrame = new AtomicReference<>(null);
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition frameAvailable = lock.newCondition();

    /**
     * Producer gọi: Đưa frame mới vào.
     * Nếu có frame cũ chưa kịp gửi -> Ghi đè (Drop frame cũ) ngay lập tức không nghẽn.
     */
    public void push(T frame) {
        latestFrame.set(frame);
        lock.lock();
        try {
            frameAvailable.signal(); // Báo hiệu cho Send Thread có frame mới
        } finally {
            lock.unlock();
        }
    }

    /**
     * Consumer gọi: Lấy frame mới nhất ra để gửi qua Socket.
     * Block chờ nếu chưa có frame nào trong hàng đợi.
     */
    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (latestFrame.get() == null) {
                frameAvailable.await();
            }
            return latestFrame.getAndSet(null); // Lấy frame ra và reset slot về null
        } finally {
            lock.unlock();
        }
    }

    /**
     * Xóa sạch hàng đợi khi ngắt kết nối.
     */
    public void clear() {
        latestFrame.set(null);
    }
}