package org.example

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.transport.acpProtocolOnClientWebSocket
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement

private class LoggingSessionOps : ClientSessionOperations {
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?
    ): RequestPermissionResponse {
        val selected = permissions.firstOrNull()?.optionId
            ?: return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
        return RequestPermissionResponse(RequestPermissionOutcome.Selected(selected))
    }

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
        if (notification is SessionUpdate.AgentMessageChunk && notification.content is ContentBlock.Text) {
            print((notification.content as ContentBlock.Text).text)
        }
    }
}

fun main(): Unit = runBlocking {
    val httpClient = HttpClient(CIO) {
        install(WebSockets)
    }

    val protocol = httpClient.acpProtocolOnClientWebSocket(
        url = "ws://localhost:8080/acp",
        protocolOptions = ProtocolOptions()
    )

    val client = Client(protocol = protocol)
    protocol.start()

    client.initialize(
        ClientInfo(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            capabilities = ClientCapabilities(
                fs = FileSystemCapability(readTextFile = true, writeTextFile = true)
            )
        )
    )

    val opsFactory = ClientOperationsFactory { _, _ -> LoggingSessionOps() }
    val session = client.newSession(
        SessionCreationParameters(cwd = "/tmp", mcpServers = emptyList()),
        opsFactory
    )

    session.prompt(listOf(ContentBlock.Text("Hello ACP server over WebSocket. Please echo this."))).collect { event ->
        when (event) {
            is Event.SessionUpdateEvent -> {
                val update = event.update
                if (update is SessionUpdate.AgentMessageChunk && update.content is ContentBlock.Text) {
                    print((update.content as ContentBlock.Text).text)
                }
            }
            is Event.PromptResponseEvent -> println("\nStop reason: ${event.response.stopReason}")
        }
    }

    httpClient.close()
    protocol.close()
}
