package net.portswigger

import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NotificationForwardingTest {
    private var serverPort = 0
    private lateinit var client: SseClient

    @BeforeEach
    fun setup() {
        serverPort = TestMcpServer.startServer()

        client = SseClient(
            sseUrl = "http://localhost:$serverPort",
            clientInfo = Implementation("test-client", "1.0.0")
        )

        runBlocking {
            client.connect()
        }
    }

    @AfterEach
    fun tearDown() {
        TestMcpServer.stopServer()
        client.close()
    }

    @Test
    fun `client should successfully handle ping request`() = runBlocking {
        val result = client.withConnection { c ->
            c.ping()
        }

        assertNotNull(result, "Ping result should not be null")
        assertEquals(EmptyResult(), result, "Ping should return EmptyResult")
    }

    @Test
    fun `client should handle reconnection after disconnect`() = runBlocking {
        val initialResult = client.withConnection { c ->
            c.ping()
        }
        assertEquals(EmptyResult(), initialResult)

        setConnectionState(client, ConnectionState.DISCONNECTED)

        val reconnectResult = client.withConnection { c ->
            c.ping()
        }

        assertNotNull(reconnectResult, "Ping after reconnection should succeed")
        assertEquals(EmptyResult(), reconnectResult)
        assertEquals(ConnectionState.CONNECTED, client.connectionState.value,
            "Connection state should be CONNECTED after successful reconnection")
    }

    @Test
    fun `client should successfully call tool through reconnection`() = runBlocking {
        val result = client.withConnection { c ->
            c.callTool("kotlin-sdk-tool", emptyMap())
        }

        assertNotNull(result, "Tool call should succeed")
        val content = result.content.firstOrNull() as? TextContent
        assertNotNull(content, "Result should contain TextContent")
        assertEquals("Hello, world!", content.text, "Tool call should return expected result")

        TestMcpServer.stopServer()
        Thread.sleep(500)
        serverPort = TestMcpServer.startServer(serverPort)
        Thread.sleep(500)

        setConnectionState(client, ConnectionState.DISCONNECTED)

        val reconnected = client.connect()
        assertTrue(reconnected, "Should successfully reconnect")

        val resultAfterReconnect = client.withConnection { c ->
            c.callTool("kotlin-sdk-tool", emptyMap())
        }

        assertNotNull(resultAfterReconnect, "Tool call after reconnection should succeed")
        val contentAfterReconnect = resultAfterReconnect.content.firstOrNull() as? TextContent
        assertNotNull(contentAfterReconnect, "Result after reconnection should contain TextContent")
        assertEquals("Hello, world!", contentAfterReconnect.text,
            "Tool call after reconnection should return expected result")
    }

    private fun setConnectionState(client: SseClient, state: ConnectionState) {
        val stateField = client.javaClass.getDeclaredField("connectionStateMutableStateFlow")
        stateField.isAccessible = true
        val stateFlow = stateField.get(client)

        val valueMethod = stateFlow.javaClass.getDeclaredMethod("setValue", Any::class.java)
        valueMethod.isAccessible = true
        valueMethod.invoke(stateFlow, state)
    }
}
