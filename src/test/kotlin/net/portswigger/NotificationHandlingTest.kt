package net.portswigger

import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NotificationHandlingTest {
    private var serverPort = 0
    private lateinit var sseClient: SseClient

    @BeforeEach
    fun setup() {
        serverPort = TestMcpServer.startServer()

        sseClient = SseClient(
            sseUrl = "http://localhost:$serverPort",
            clientInfo = Implementation("test-client", "1.0.0")
        )
    }

    @AfterEach
    fun tearDown() {
        TestMcpServer.stopServer()
        sseClient.close()
    }

    @Test
    fun `client should successfully connect and maintain connection state`() = runBlocking {
        assertEquals(ConnectionState.DISCONNECTED, sseClient.connectionState.value)

        val connected = sseClient.connect()
        assertEquals(true, connected, "Should successfully connect to test server")

        assertEquals(ConnectionState.CONNECTED, sseClient.connectionState.value)

        val result = sseClient.withConnection { c ->
            c.ping()
        }
        assertEquals(EmptyResult(), result)
    }

    @Test
    fun `connection should recover from server restart`() = runBlocking {
        sseClient.connect()
        assertEquals(ConnectionState.CONNECTED, sseClient.connectionState.value)

        TestMcpServer.stopServer()
        Thread.sleep(500)
        serverPort = TestMcpServer.startServer(serverPort)
        Thread.sleep(500)

        TestUtils.setConnectionState(sseClient, ConnectionState.DISCONNECTED)

        val reconnected = sseClient.connect()
        assertEquals(true, reconnected, "Should successfully reconnect to restarted server")
        assertEquals(ConnectionState.CONNECTED, sseClient.connectionState.value)
    }
}
