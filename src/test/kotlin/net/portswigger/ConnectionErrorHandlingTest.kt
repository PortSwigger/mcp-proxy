package net.portswigger

import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConnectionErrorHandlingTest {
    private var serverPort = 0
    private lateinit var client: SseClient

    @BeforeEach
    fun setup() {
        serverPort = TestMcpServer.startServer()
        client = SseClient(
            sseUrl = "http://localhost:$serverPort",
            clientInfo = Implementation("test-client", "1.0.0")
        )
    }

    @AfterEach
    fun tearDown() {
        TestMcpServer.stopServer()
        client.close()
    }

    @Test
    fun `client should handle connection timeout`(): Unit = runBlocking {
        val timeoutClient = SseClient(
            sseUrl = "http://localhost:54321", // Non-existent port
            clientInfo = Implementation("test-client", "1.0.0")
        )

        try {
            val connected = timeoutClient.connect()

            assertTrue(!connected, "Connection should fail")

            assertEquals(ConnectionState.DISCONNECTED, timeoutClient.connectionState.value)

            val monitorJobField = timeoutClient.javaClass.getDeclaredField("monitorJob")
            monitorJobField.isAccessible = true
            val monitorJob = monitorJobField.get(timeoutClient)
            assertNotNull(monitorJob, "Monitor job should be active after connection failure")

            assertThrows<IOException> {
                timeoutClient.withConnection { c ->
                    c.callTool("test-tool", emptyMap())
                }
            }
        } finally {
            timeoutClient.close()
        }
    }

    @Test
    fun `client should attempt reconnection during withConnection when disconnected`(): Unit = runBlocking {
        client.connect()

        TestUtils.setConnectionState(client, ConnectionState.DISCONNECTED)

        try {
            client.withConnection { c ->
                c.callTool("kotlin-sdk-tool", emptyMap())
            }
        } catch (e: IOException) {
            assertTrue(e.message?.contains("reconnection attempts") == true,
                "Exception should mention reconnection attempts")
        }
    }

    @Test
    fun `client should start monitoring after reconnection failure`(): Unit = runBlocking {
        client.connect()
        assertEquals(ConnectionState.CONNECTED, client.connectionState.value)

        TestMcpServer.stopServer()
        delay(500)

        TestUtils.setConnectionState(client, ConnectionState.DISCONNECTED)

        assertThrows<IOException> {
            client.withConnection { c ->
                c.callTool("test-tool", emptyMap())
            }
        }

        val monitorJobField = client.javaClass.getDeclaredField("monitorJob")
        monitorJobField.isAccessible = true
        val monitorJob = monitorJobField.get(client)
        assertNotNull(monitorJob, "Monitor job should be active after reconnection failure")
    }

    @Test
    fun `client should recover when server becomes available during monitoring`() = runBlocking {
        val monitoringClient = SseClient(
            sseUrl = "http://localhost:$serverPort", // We'll stop the server first
            clientInfo = Implementation("test-client", "1.0.0")
        )

        try {
            TestMcpServer.stopServer()

            val connected = monitoringClient.connect()
            assertTrue(!connected, "Connection should fail when server is not available")

            // Restart server
            serverPort = TestMcpServer.startServer(serverPort)

            withTimeout(10000) {
                while (monitoringClient.connectionState.value != ConnectionState.CONNECTED) {
                    delay(500)
                }
            }

            assertEquals(ConnectionState.CONNECTED, monitoringClient.connectionState.value)

            val result = monitoringClient.withConnection { c ->
                c.callTool("kotlin-sdk-tool", emptyMap())
            }

            assertNotNull(result, "Tool call should succeed after reconnection")
            assertEquals("Hello, world!", (result.content.first() as TextContent).text)
        } finally {
            monitoringClient.close()
        }
    }
}
