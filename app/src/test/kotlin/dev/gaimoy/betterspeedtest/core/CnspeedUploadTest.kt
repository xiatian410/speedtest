package dev.gaimoy.betterspeedtest.core

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CnspeedUploadTest {
    @Test fun aServerThatReadsDataButNeverRepliesIsNotConfirmed() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val accepted = CompletableDeferred<Socket>()
        val received = CompletableDeferred<Unit>()
        val serverJob = launch(Dispatchers.IO) {
            try { server.accept().use { socket ->
                accepted.complete(socket)
                val input = socket.getInputStream()
                val buffer = ByteArray(4096)
                while (isActive && input.read(buffer) >= 0) {
                    received.complete(Unit)
                    delay(10) // Keep the local regression small; do not flood loopback with a 900 MB body.
                }
            } } catch (_: IOException) { /* Expected when the regression closes its local server. */ }
        }
        val gotOK = AtomicBoolean(false)
        val bytes = AtomicLong()
        val root = SupervisorJob()
        val scope = CoroutineScope(root + Dispatchers.IO)
        val worker = scope.launch {
            Engine.cnspeedUpload("127.0.0.1", server.localPort.toString(), "/upload", "test", gotOK).create()(this, bytes)
        }
        try {
            withTimeout(2000) { received.await(); while (bytes.get() == 0L) delay(10) }
            withTimeout(1000) { worker.cancelAndJoin() }
            assertTrue(bytes.get() > 0)
            assertFalse(gotOK.get())
        } finally {
            root.cancelAndJoin()
            serverJob.cancel()
            server.close()
            if (accepted.isCompleted) accepted.await().close()
            serverJob.cancelAndJoin()
        }
    }
}
