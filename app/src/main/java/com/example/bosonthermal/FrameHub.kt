package com.example.bosonthermal

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Latest-frame hand-off between the USB thread (publish) and consumer threads (await). */
class FrameHub(size: Int) {
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val latest = ByteArray(size)
    private var seq = 0L

    fun reset() = lock.withLock { seq = 0L }

    /** USB thread: copy only. */
    fun publish(f: ByteArray) {
        lock.withLock {
            System.arraycopy(f, 0, latest, 0, latest.size)
            seq++
            cond.signalAll()
        }
    }

    fun wake() = lock.withLock { cond.signalAll() }

    /** Blocks until a frame newer than [lastSeq] exists and copies it to [dst]; returns its seq or -1 if no longer alive. */
    fun await(lastSeq: Long, dst: ByteArray, alive: () -> Boolean): Long {
        var result = -1L
        lock.withLock {
            while (alive() && seq == lastSeq) {
                try { cond.await(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
            }
            if (alive()) {
                System.arraycopy(latest, 0, dst, 0, dst.size)
                result = seq
            }
        }
        return result
    }
}
