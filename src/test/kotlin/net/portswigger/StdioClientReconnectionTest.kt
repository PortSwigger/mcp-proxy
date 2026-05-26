package net.portswigger

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.slf4j.LoggerFactory
import java.io.File
import java.net.ServerSocket
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end test that simulates:
 * 1. A STDIO MCP Client
 * 2. The proxy connecting to an SSE server
 * 3. Server restart and session recovery
 */
class StdioClientReconnectionTest {
    private val logger = LoggerFactory.getLogger(StdioClientReconnectionTest::class.java)
    private var serverPort = 0

    private lateinit var testClient: TestStdioMcpClient
    private lateinit var proxyProcess: Process

    private fun findAvailablePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    @BeforeEach
    fun setup(): Unit = runBlocking {
        serverPort = findAvailablePort()

        serverPort = TestMcpServer.startServer(serverPort)
        logger.info("Started test server on port $serverPort")

        val gradleWrapper = if (File("./gradlew").exists()) "./gradlew" else "gradlew"

        ProcessBuilder(
            gradleWrapper, "shadowJar"
        ).directory(File(".")).inheritIO().start().waitFor()

        val jarFile =
            File("build/libs").listFiles()?.filter { it.name.endsWith("all.jar") }?.maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException("Could not find jar file")

        logger.info("Using jar file: ${jarFile.absolutePath}")

        proxyProcess = ProcessBuilder(
            "java",
            "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug",
            "-jar",
            jarFile.absolutePath,
            "--sse-url",
            "http://localhost:$serverPort"
        ).directory(File(".")).redirectError(ProcessBuilder.Redirect.INHERIT).start()

        delay(3.seconds)

        testClient = TestStdioMcpClient()

        testClient.connectToServer(
            proxyProcess.inputStream, proxyProcess.outputStream
        )

        logger.info("Test client connected to proxy")

        testClient.ping()
    }

    @AfterEach
    fun tearDown() = runBlocking {
        try {
            if (::testClient.isInitialized) {
                testClient.close()
            }
        } catch (e: Exception) {
            logger.warn("Error closing test client: ${e.message}")
        }

        try {
            if (::proxyProcess.isInitialized) {
                proxyProcess.destroy()
                if (proxyProcess.isAlive) {
                    delay(1000)
                    proxyProcess.destroyForcibly()
                }
            }
        } catch (e: Exception) {
            logger.warn("Error shutting down proxy process: ${e.message}")
        }

        TestMcpServer.stopServer()
    }

    /**
     * Test that verifies the full end-to-end flow works correctly during server restart.
     * This simulates a real client like Claude Desktop connected to our proxy,
     * which then connects to the SSE server.
     *
     * The test flow:
     * 1. Initial ping works (verified in setup)
     * 2. Server is stopped and restarted
     * 3. Wait for reconnection
     * 4. Ping after reconnection should work
     * 5. Tool call after reconnection should work
     */
    @Test
    fun `should handle server restart and recover transparently for STDIO clients`(): Unit = runBlocking {
        val firstResult = testClient.testTool()
        val textContent = firstResult?.content?.firstOrNull() as? TextContent
        assertEquals(
            textContent?.text,
            "Hello, world!",
            "Initial tool call should return the expected message"
        )
        logger.info("Restarting server to trigger session errors...")
        TestMcpServer.stopServer()
        delay(2000)
        serverPort = TestMcpServer.startServer(serverPort)
        logger.info("Server restarted on port $serverPort")

        logger.info("Waiting for reconnection...")
        delay(4.seconds)

        logger.info("Sending ping after server restart...")

        testClient.ping()

        logger.info("Testing tool call after server restart...")

        val result = testClient.testTool()
        val content = result?.content?.firstOrNull() as? TextContent
        assertNotNull(content, "Tool call result content should not be null")
        assertEquals("Hello, world!", content?.text, "Tool call should return the expected message")
    }
    
    /**
     * Test that verifies the tool call handling during "Session not found" errors.
     * 
     * Without the try-catch handling in the ToolsCall request handler (Main.kt),
     * this test would fail with a "Session not found" error.
     * 
     * The key difference from the other test is that we restart the server and
     * immediately try to make a tool call WITHOUT first making a ping request.
     * The ping would normally trigger a reconnection, but this test simulates
     * a more direct scenario where the client makes a tool call right after
     * the server restart, which would fail without the specific error handling.
     */
    @Test
    fun `should handle session not found error during tool call after server restart`(): Unit = runBlocking {
        val firstResult = testClient.testTool()
        val initialContent = firstResult?.content?.firstOrNull() as? TextContent
        assertEquals(
            initialContent?.text,
            "Hello, world!",
            "Initial tool call should return the expected message"
        )
        
        logger.info("Restarting server to create a session not found condition...")
        TestMcpServer.stopServer()
        delay(1000)
        serverPort = TestMcpServer.startServer(serverPort)
        logger.info("Server restarted on port $serverPort")
        
        delay(1.seconds)
        
        val result = testClient.testTool()
        
        val content = result?.content?.firstOrNull() as? TextContent
        assertNotNull(content, "Tool call result content should not be null")
        assertEquals("Hello, world!", content?.text, "Tool call should return the expected message")
    }
}