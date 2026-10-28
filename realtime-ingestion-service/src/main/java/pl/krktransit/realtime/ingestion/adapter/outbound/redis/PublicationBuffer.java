package pl.krktransit.realtime.ingestion.adapter.outbound.redis;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

final class PublicationBuffer {
    private final long capacityBytes;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final Condition room = lock.newCondition();
    private final ArrayDeque<PendingRecord> queued = new ArrayDeque<>();
    private long pendingBytes;
    private boolean acquisitionPaused;
    private boolean closed;

    PublicationBuffer(long capacityBytes) {
        if (!hasValidCapacity(capacityBytes)) {
            throw new IllegalArgumentException("Publication buffer capacity must be positive");
        }
        this.capacityBytes = capacityBytes;
    }

    private static boolean hasValidCapacity(long capacityBytes) {
        return capacityBytes > 0;
    }

    boolean offer(List<PendingRecord> records) {
        long size = records.stream().mapToLong(PendingRecord::size).sum();
        lock.lock();
        try {
            if (closed) {
                return false;
            }
            if (!fits(size)) {
                acquisitionPaused = !isDrained();
                return false;
            }
            queued.addAll(records);
            pendingBytes += size;
            available.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean fits(long size) {
        return size <= capacityBytes - pendingBytes;
    }

    private boolean isDrained() {
        return pendingBytes <= capacityBytes / 2;
    }

    void awaitRoom() throws InterruptedException {
        lock.lock();
        try {
            while (shouldWaitForRoom()) {
                room.await();
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean shouldWaitForRoom() {
        return !closed && hasNoRoom();
    }

    private boolean hasNoRoom() {
        return acquisitionPaused || pendingBytes >= capacityBytes;
    }

    private void resumeIfDrained() {
        if (isDrained()) {
            acquisitionPaused = false;
        }
        room.signalAll();
    }

    PendingRecord take() throws InterruptedException {
        lock.lock();
        try {
            while (shouldWaitForRecord()) {
                available.await();
            }
            return queued.poll();
        } finally {
            lock.unlock();
        }
    }

    private boolean shouldWaitForRecord() {
        return queued.isEmpty() && !closed;
    }

    void release(PendingRecord record) {
        lock.lock();
        try {
            pendingBytes -= record.size();
            resumeIfDrained();
        } finally {
            lock.unlock();
        }
    }

    int discardQueued() {
        lock.lock();
        try {
            int discarded = queued.size();
            queued.forEach(record -> pendingBytes -= record.size());
            queued.clear();
            resumeIfDrained();
            return discarded;
        } finally {
            lock.unlock();
        }
    }

    void close() {
        lock.lock();
        try {
            closed = true;
            available.signalAll();
            room.signalAll();
        } finally {
            lock.unlock();
        }
    }

    long pendingBytes() {
        lock.lock();
        try {
            return pendingBytes;
        } finally {
            lock.unlock();
        }
    }
}
