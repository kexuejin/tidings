package app.relay.app

import java.util.concurrent.locks.ReentrantLock

private class ReentrantStateLock : StateLock {
    private val delegate = ReentrantLock()

    override fun lock() {
        delegate.lock()
    }

    override fun unlock() {
        delegate.unlock()
    }
}

internal actual fun createStateLock(): StateLock = ReentrantStateLock()
