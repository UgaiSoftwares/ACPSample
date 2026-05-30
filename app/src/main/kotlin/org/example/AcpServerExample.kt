package org.example

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.transport.acpProtocolOnServerWebSocket
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement

private class EchoAgentSession(
    override val sessionId: SessionId
) : AgentSession {
    override suspend fun prompt(
        content: List<ContentBlock>,
        _meta: JsonElement?
    ): Flow<Event> = flow {
        val userText = content
            .filterIsInstance<ContentBlock.Text>()
            .joinToString(" ") { it.text }
            .ifBlank { "(empty prompt)" }

        emit(
            Event.SessionUpdateEvent(
                SessionUpdate.AgentMessageChunk(
                    ContentBlock.Text("ACP server received: $userText")
                )
            )
        )
        emit(Event.PromptResponseEvent(PromptResponse(StopReason.END_TURN)))
    }
}

private class EchoAgentSupport : AgentSupport {
    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo = AgentInfo(
        protocolVersion = LATEST_PROTOCOL_VERSION,
        capabilities = AgentCapabilities()
    )

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
        val id = SessionId("session-${System.currentTimeMillis()}")
        return EchoAgentSession(id)
    }

    override suspend fun loadSession(
        sessionId: SessionId,
        sessionParameters: SessionCreationParameters
    ): AgentSession = EchoAgentSession(sessionId)
}

fun main() {
    val server = embeddedServer(CIO, port = 8080) {
        install(WebSockets)
        routing {
            acpProtocolOnServerWebSocket(path = "/acp", protocolOptions = ProtocolOptions()) { protocol: Protocol ->
                Agent(protocol = protocol, agentSupport = EchoAgentSupport())
                protocol.start()
            }
        }
    }

    println("ACP WebSocket server listening at ws://localhost:8080/acp")
    server.start(wait = true)
}
