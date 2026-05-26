package net.portswigger

import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerRestartTest {
    private var serverPort = 0
    private lateinit var client: SseClient

    @BeforeEach
    fun setup() {
        serverPort = TestMcpServer.startServer(serverPort)
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
    fun `client should handle server shutdown and reconnect automatically`() = runBlocking {
        val initialConnected = client.connect()
        assertTrue(initialConnected, "Initial connection should succeed")
        assertEquals(ConnectionState.CONNECTED, client.connectionState.value)

        val initialResult = client.withConnection { c ->
            c.callTool("kotlin-sdk-tool", emptyMap())
        }
        assertNotNull(initialResult, "Initial tool call should succeed")
        assertEquals("Hello, world!", (initialResult.content.first() as TextContent).text)

        TestMcpServer.stopServer()
        delay(1000)

        try {
            client.withConnection { c ->
                c.callTool("kotlin-sdk-tool", emptyMap())
            }
        } catch (e: Exception) {
            // Expected exception
        }

        serverPort = TestMcpServer.startServer(serverPort)
        delay(3000)

        val result = client.withConnection { c ->
            c.callTool("kotlin-sdk-tool", emptyMap())
        }

        assertNotNull(result, "Tool call after reconnection should succeed")
        assertEquals("Hello, world!", (result.content.first() as TextContent).text)
    }

    @Test
    fun `client should handle server restart and reconnect automatically`() = runBlocking {
        val initialConnected = client.connect()
        assertTrue(initialConnected, "Initial connection should succeed")
        assertEquals(ConnectionState.CONNECTED, client.connectionState.value)

        val initialResult = client.withConnection { c ->
            c.callTool("kotlin-sdk-tool", emptyMap())
        }
        assertNotNull(initialResult, "Initial tool call should succeed")
        assertEquals("Hello, world!", (initialResult.content.first() as TextContent).text)

        TestMcpServer.stopServer()
        delay(1000)

        serverPort = TestMcpServer.startServer(serverPort)
        delay(3000)

        val result = client.withConnection { c ->
            c.callTool("kotlin-sdk-tool", emptyMap())
        }

        assertNotNull(result, "Tool call after reconnection should succeed")
        assertEquals("Hello, world!", (result.content.first() as TextContent).text)
    }

    @Test
    fun `client should automatically reconnect if disconnected`() = runBlocking {
        client.connect()

        TestUtils.setConnectionState(client, ConnectionState.DISCONNECTED)

        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)

        val result = client.withConnection { c ->
            c.callTool("kotlin-sdk-tool", emptyMap())
        }

        assertNotNull(result, "Tool call after forced disconnect should succeed")
        assertEquals("Hello, world!", (result.content.first() as TextContent).text)
        assertEquals(ConnectionState.CONNECTED, client.connectionState.value)
    }
}
