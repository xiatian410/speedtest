package dev.gaimoy.betterspeedtest.core

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class CoreProtocolTest {
    @Test fun ooklaUrlWinsOverLegacyHostContainingPort() {
        val node = Node("sample", "ookla", hostIp = "speed.example:8080", dlUrl = "https://speed.example:8080/download")
        assertEquals("speed.example" to 8080, resolvePingTarget(node))
    }

    @Test fun dnsAndIpv6EndpointsRetainTheirPorts() {
        assertEquals("speed.example" to 8080, parseEndpoint("speed.example:8080", https = true))
        assertEquals("2001:db8::1" to 8443, parseEndpoint("[2001:db8::1]:8443", https = true))
        assertEquals("2001:db8::1" to 80, parseEndpoint("2001:db8::1"))
        assertEquals("2001:db8::1" to 8080, parseEndpoint("2001:db8::1", "8080"))
        assertNull(parseEndpoint("speed.example", "70000"))
    }

    @Test fun identityIgnoresLabelsAndCacheTokensButNotPort() {
        val original = Node("old", "ookla", dlUrl = "https://speed.example:8080/download?nocache=1")
        assertEquals(original.stableId(), original.copy(name = "new", dlUrl = "https://speed.example:8080/download?nocache=2").stableId())
        assertNotEquals(original.stableId(), original.copy(dlUrl = "https://speed.example:8443/download").stableId())
    }

    @Test fun fragmentedHeadersDoNotConsumeBody() {
        val input = ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello".toByteArray())
        val head = readHttpResponseHead(input)
        assertEquals(200, head.status)
        assertEquals("5", head.headers["content-length"])
        assertEquals("hello", input.readBytes().toString(Charsets.UTF_8))
    }

    @Test fun interimResponsesAndChunkFramingAreNotCounted() {
        val input = ByteArrayInputStream(("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\n" +
            "Transfer-Encoding: chunked\r\n\r\n3;ext=1\r\nabc\r\n2\r\nde\r\n0\r\n\r\n").toByteArray())
        val head = readHttpResponseHead(input)
        var bytes = 0
        readHttpBody(input, head, { true }) { bytes += it }
        assertEquals(5, bytes)
    }

    @Test(expected = IOException::class) fun oversizedHeadersAreRejected() {
        readHttpResponseHead(ByteArrayInputStream("HTTP/1.1 200 OK\r\nLong: abcdefghijklmnopqrstuvwxyz\r\n\r\n".toByteArray()), 24)
    }

    @Test(expected = IOException::class) fun truncatedEntityIsNotSilentlyAccepted() {
        val input = ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort".toByteArray())
        readHttpBody(input, readHttpResponseHead(input), { true }) { }
    }
}
