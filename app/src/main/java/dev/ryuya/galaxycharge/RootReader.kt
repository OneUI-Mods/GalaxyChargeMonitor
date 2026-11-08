package dev.ryuya.galaxycharge

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A bounded, one-shot reader. There is no persistent root daemon or writable command API. */
internal class RootReader : AutoCloseable {
    private val current = AtomicReference<Process?>()
    private val watchdog = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    @Volatile private var closed = false

    fun snapshot(): Snapshot = Frames.snapshot(execute(ReadCommands.snapshot))

    fun catalog(): List<Property> = PropertyParser.parse(execute(ReadCommands.catalog))

    private fun execute(command: String): String {
        val (process, timeout) =
            synchronized(this) {
                check(!closed) { "Reader closed" }
                check(current.get() == null) { "Read already running" }
                val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                current.set(process)
                process to watchdog.schedule({ process.destroyForcibly() }, 15, TimeUnit.SECONDS)
            }
        try {
            val result = StringBuilder()
            process.inputStream.bufferedReader().use { input ->
                val buffer = CharArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(result.length + n <= 2_000_000) { "Response too large" }
                    result.append(buffer, 0, n)
                }
            }
            check(process.waitFor(1, TimeUnit.SECONDS) && process.exitValue() == 0) {
                "Root read denied or timed out"
            }
            return result.toString()
        } finally {
            timeout.cancel(false)
            current.compareAndSet(process, null)
            process.destroy()
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        current.getAndSet(null)?.destroyForcibly()
        watchdog.shutdownNow()
    }
}
