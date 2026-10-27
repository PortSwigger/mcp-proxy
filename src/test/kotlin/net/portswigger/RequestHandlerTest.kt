package net.portswigger

import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds

class RequestHandlerTest {
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
    fun `SDK timeout cancels upstream handler without replay`() = runBlocking {
        var attempts = 0
        val cancelled = CompletableDeferred<Unit>()
        TestMcpServer.getInstance().server.addTool(name = "waiting-tool", description = "Wait for cancellation") {
            attempts++
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        assertFailsWith<IOException> {
            client.withConnection { c ->
                c.callTool("waiting-tool", emptyMap(), options = RequestOptions(timeout = 250.milliseconds))
            }
        }
        withTimeout(5000) { cancelled.await() }
        assertEquals(1, attempts)
    }

    @Test
    fun `cancelled operation is not retried and does not disconnect client`() = runBlocking {
        var attempts = 0
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            client.withConnection {
                attempts++
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(5000) { entered.await() }
        job.cancelAndJoin()
        assertEquals(1, attempts)
        assertEquals(ConnectionState.CONNECTED, client.connectionState.value)
    }

    @Test
    fun `failed operation is not replayed`() = runBlocking {
        var attempts = 0
        assertFailsWith<IOException> {
            client.withConnection<Unit> {
                attempts++
                throw IOException("Response lost after sending")
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `client should successfully call tool`() = runBlocking {
        val result = client.withConnection { c ->
            c.callTool("kotlin-sdk-tool", emptyMap())
        }

        assertNotNull(result, "Tool call result should not be null")

        val content = result.content.firstOrNull() as? TextContent
        assertNotNull(content, "Tool call should return content")
        assertEquals("Hello, world!", content.text, "Tool call should return expected message")
    }

    @Test
    fun `client should automatically reconnect after server restart when making requests`() = runBlocking {
        val initialResult = client.withConnection { c ->
            c.callTool("kotlin-sdk-tool", emptyMap())
        }
        assertNotNull(initialResult, "Initial tool call should succeed")

        TestUtils.setConnectionState(client, ConnectionState.DISCONNECTED)

        val resultAfterForceDisconnect = client.withConnection { c ->
            c.callTool("kotlin-sdk-tool", emptyMap())
        }

        assertNotNull(resultAfterForceDisconnect, "Tool call after force disconnect should succeed")
        assertEquals(
            "Hello, world!",
            (resultAfterForceDisconnect.content.first() as TextContent).text,
            "Tool call should return the expected result after reconnection"
        )

        assertEquals(ConnectionState.CONNECTED, client.connectionState.value)
    }

    @Test
    fun `server restart should trigger automatic reconnection for tool calls`() = runBlocking {
        val initialResult = client.withConnection { c ->
            c.callTool("kotlin-sdk-tool", emptyMap())
        }
        assertNotNull(initialResult, "Initial tool call should succeed")

        TestMcpServer.stopServer()
        serverPort = TestMcpServer.startServer(serverPort)

        try {
            client.withConnection { c ->
                c.callTool("kotlin-sdk-tool", emptyMap())
            }
        } catch (e: Exception) {
            client.connect()
        }

        val resultAfterRestart = client.withConnection { c ->
            c.callTool("kotlin-sdk-tool", emptyMap())
        }

        assertNotNull(resultAfterRestart, "Tool call after server restart should succeed")
        assertEquals(
            "Hello, world!",
            (resultAfterRestart.content.first() as TextContent).text,
            "Tool call should return the expected result after server restart"
        )
    }
}
