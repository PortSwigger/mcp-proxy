package net.portswigger

import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.net.ServerSocket

/**
 * Test server utility class for MCP testing.
 * Provides a reusable MCP server for other tests.
 */
class TestMcpServer {
    private var port: Int = 0
    private var serverEngine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val server: Server = configureServer()

    fun start(port: Int = 0): Int {
        this.port = if (port == 0) findAvailablePort() else port

        serverEngine = embeddedServer(CIO, host = "0.0.0.0", port = this.port) {
            mcp {
                server
            }
        }.start(wait = false)

        Thread.sleep(500)

        return this.port
    }

    fun stop() {
        serverEngine?.stop(1000, 1000)
        serverEngine = null
        port = 0
    }

    fun getPort(): Int = port

    fun isRunning(): Boolean = serverEngine != null && port != 0

    private fun findAvailablePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private fun configureServer(): Server {
        val server = Server(
            Implementation(
                name = "mcp-kotlin test server",
                version = "0.1.0"
            ),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                )
            )
        )

        server.addTool(
            name = "kotlin-sdk-tool",
            description = "A test tool",
        ) {
            CallToolResult(
                content = listOf(TextContent("Hello, world!"))
            )
        }

        return server
    }

    companion object {
        private var instance: TestMcpServer? = null

        @JvmStatic
        fun getInstance(): TestMcpServer {
            return instance ?: TestMcpServer().also { instance = it }
        }

        @JvmStatic
        fun startServer(port: Int = 0): Int {
            val server = getInstance()
            return if (!server.isRunning()) {
                server.start(port)
            } else {
                server.getPort()
            }
        }

        @JvmStatic
        fun stopServer() {
            instance?.stop()
            instance = null
        }
    }
}
