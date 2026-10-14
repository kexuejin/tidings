package app.relay.app

import platform.Foundation.NSRecursiveLock

private class NsRecursiveStateLock : StateLock {
    private val delegate = NSRecursiveLock()

    override fun lock() {
        delegate.lock()
    }

    override fun unlock() {
        delegate.unlock()
    }
}

internal actual fun createStateLock(): StateLock = NsRecursiveStateLock()
