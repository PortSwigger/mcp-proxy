package net.portswigger

import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SseClientTest {
    private val sseUrl = "http://localhost:12345"
    private lateinit var client: SseClient
    
    @BeforeEach
    fun setup() {
        client = SseClient(
            sseUrl = sseUrl,
            clientInfo = Implementation("test-client", "1.0.0")
        )
    }
    
    @AfterEach
    fun tearDown() {
        client.close()
    }
    
    @Test
    fun `initial connection state should be disconnected`() = runTest {
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
    }
    
    @Test
    fun `connection state should update correctly during connect attempts`() = runTest {
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
        
        val stateChanges = mutableListOf<ConnectionState>()
        val job = launch {
            client.connectionState.collect { state ->
                stateChanges.add(state)
            }
        }
        
        val connected = client.connect()
        assertFalse(connected, "Connect should fail when server is not available")
        
        job.cancel()
        
        // DISCONNECTED -> CONNECTING -> DISCONNECTED
        assertTrue(stateChanges.contains(ConnectionState.CONNECTING), 
            "State should transition to CONNECTING during connect attempt")
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value,
            "State should return to DISCONNECTED after failed connect")
    }
    
    @Test
    fun `connection methods should work as expected`() = runTest {
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
        
        val connected = client.connect()
        assertFalse(connected, "Connect should fail when server is not available")
        
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
        
        val exception = runCatching {
            client.withConnection { c ->
                c.ping()
            }
        }.exceptionOrNull()
        
        assertNotNull(exception, "Should throw an exception when unable to connect")
    }
    
    @Test
    fun `monitoring should start when connection fails`() = runTest {
        val field = client.javaClass.getDeclaredField("monitorJob")
        field.isAccessible = true
        
        assertNull(field.get(client), "Monitor job should be null initially")
        
        client.connect()
        
        assertNotNull(field.get(client), "Monitor job should be started after failed connection")
    }
}