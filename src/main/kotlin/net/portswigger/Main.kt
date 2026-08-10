package net.portswigger

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.CancelledNotification
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.InitializedNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.Notification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.PromptListChangedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ResourceListChangedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.RootsListChangedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ToolListChangedNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val loggerMain = LoggerFactory.getLogger("net.portswigger.Main")

data class SseToStdioArgs(
    val sseUrl: String
)

fun sseToStdio(args: SseToStdioArgs) = runBlocking {
    val sseUrl = args.sseUrl
    val sseClientManager = SseClient(
        sseUrl = sseUrl, clientInfo = Implementation(
            name = "burp-proxy", version = "1.0.0"
        )
    )

    val connected = sseClientManager.connect()
    if (!connected) {
        loggerMain.warn("Please check if the server is running and the URL is correct")
        loggerMain.info("Will attempt to reconnect automatically when needed")
    }

    val sseClient = sseClientManager.getClient()
    val stdioServer = Server(
        serverInfo = sseClient.serverVersion ?: Implementation("burp-suite", "1.0"), options = ServerOptions(
            capabilities = sseClient.serverCapabilities ?: ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
                prompts = ServerCapabilities.Prompts(listChanged = true),
                resources = ServerCapabilities.Resources(subscribe = false, listChanged = true),
            )
        )
    )

    sseClientManager.onConnected {
        registerFeatures(sseClientManager.getClient(), stdioServer, sseClientManager)
    }

    registerFeatures(sseClient, stdioServer, sseClientManager)

    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(), System.out.asSink().buffered()
    ) {}

    runBlocking {
        try {
            val sessionRef = CompletableDeferred<ServerSession>()
            setupNotificationForwarding(sseClient, sessionRef, sseClientManager)
            val session = stdioServer.createSession(transport)
            sessionRef.complete(session)
            val done = Job()
            session.onClose { done.complete() }
            done.join()
        } catch (e: Exception) {
            loggerMain.error("Error in stdio server: {}", e.message, e)
        } finally {
            sseClientManager.close()
        }
    }
}

private suspend fun registerFeatures(sseClient: Client, stdioServer: Server, connectionManager: SseClient) {
    if (sseClient.serverCapabilities?.tools != null) {
        try {
            val tools = connectionManager.withConnection { c -> c.listTools() }
            tools?.tools?.forEach { tool ->
                stdioServer.addTool(tool) { request ->
                    connectionManager.withConnection { c -> c.callTool(request) }
                        ?: CallToolResult(content = emptyList(), isError = true)
                }
            }
        } catch (e: Exception) {
            loggerMain.warn("Failed to list tools from SSE server: {}", e.message)
        }
    }

    if (sseClient.serverCapabilities?.prompts != null) {
        try {
            val prompts = connectionManager.withConnection { c -> c.listPrompts() }
            prompts?.prompts?.forEach { prompt ->
                stdioServer.addPrompt(prompt) { request ->
                    connectionManager.withConnection { c -> c.getPrompt(request) }
                        ?: error("Not connected to SSE server")
                }
            }
        } catch (e: Exception) {
            loggerMain.warn("Failed to list prompts from SSE server: {}", e.message)
        }
    }

    if (sseClient.serverCapabilities?.resources != null) {
        try {
            val resources = connectionManager.withConnection { c -> c.listResources() }
            resources?.resources?.forEach { resource ->
                stdioServer.addResource(
                    uri = resource.uri,
                    name = resource.name,
                    description = resource.description ?: "",
                    mimeType = resource.mimeType ?: "text/plain"
                ) { request ->
                    connectionManager.withConnection { c -> c.readResource(request) }
                        ?: error("Not connected to SSE server")
                }
            }
        } catch (e: Exception) {
            loggerMain.warn("Failed to list resources from SSE server: {}", e.message)
        }
    }
}

private fun setupNotificationForwarding(
    sseClient: Client, sessionRef: CompletableDeferred<ServerSession>, connectionManager: SseClient
) {
    val logger = LoggerFactory.getLogger("net.portswigger.Main")

    fun <T : Notification> forwardNotification(method: Method) {
        sseClient.setNotificationHandler<T>(method) { notification ->
            connectionManager.launch {
                try {
                    val session = sessionRef.await()
                    connectionManager.withConnection { _ ->
                        session.notification(notification)
                    }
                } catch (e: Exception) {
                    logger.error("Failed to forward {} notification: {}", method.value, e.message)
                }
            }
            CompletableDeferred(Unit)
        }
    }

    forwardNotification<ToolListChangedNotification>(Method.Defined.NotificationsToolsListChanged)
    forwardNotification<ResourceListChangedNotification>(Method.Defined.NotificationsResourcesListChanged)
    forwardNotification<ResourceUpdatedNotification>(Method.Defined.NotificationsResourcesUpdated)
    forwardNotification<PromptListChangedNotification>(Method.Defined.NotificationsPromptsListChanged)
    forwardNotification<RootsListChangedNotification>(Method.Defined.NotificationsRootsListChanged)
    forwardNotification<LoggingMessageNotification>(Method.Defined.NotificationsMessage)
    forwardNotification<CancelledNotification>(Method.Defined.NotificationsCancelled)
    forwardNotification<ProgressNotification>(Method.Defined.NotificationsProgress)
    forwardNotification<InitializedNotification>(Method.Defined.NotificationsInitialized)
}

/**
 * Parse command line arguments.
 *
 * @param args Command line arguments
 * @return The SSE URL extracted from arguments or the default one
 */
fun parseCommandLineArgs(args: Array<String>): String {
    return if (args.size >= 2 && args[0] == "--sse-url") {
        args[1]
    } else {
        "http://localhost:9876"
    }
}

/**
 * Start the MCP proxy.
 *
 * @param args Command line arguments:
 * - "--sse-url <url>": The URL of the SSE MCP server (default: http://localhost:9876)
 */
fun main(args: Array<String>) {
    try {
        val sseUrl = parseCommandLineArgs(args)
        loggerMain.info("Starting Burp MCP proxy with SSE URL: {}", sseUrl)

        sseToStdio(
            SseToStdioArgs(
                sseUrl = sseUrl
            )
        )
    } catch (e: Exception) {
        loggerMain.error("Failed to start proxy: {}", e.message, e)
        exitProcess(1)
    }
}
