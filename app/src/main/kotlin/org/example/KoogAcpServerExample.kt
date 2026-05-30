package org.example

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeDoNothing
import ai.koog.agents.features.acp.AcpAgent
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaModels
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
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonElement

private fun buildKoogAcpEnabledAgent(
    sessionId: SessionId,
    protocol: Protocol,
    eventsProducer: ProducerScope<Event>
): AIAgent<String, String> {
    val textGraphStrategy = strategy<String, String>("koog-acp-server-echo") {
        val passThrough by nodeDoNothing<String>("pass-through")
        edge(nodeStart.forwardTo(passThrough))
        edge(passThrough.forwardTo(nodeFinish))
    }

    val promptExecutor = PromptExecutor.builder()
        .ollama()
        .build()

    val config = AIAgentConfig.withSystemPrompt(
        "Echo text from ACP prompts.",
        OllamaModels.Meta.LLAMA_3_2,
        "en",
        8
    )

    return AIAgent(
        promptExecutor = promptExecutor,
        agentConfig = config,
        strategy = textGraphStrategy
    ) {
        install(AcpAgent) {
            this.sessionId = sessionId.value
            this.protocol = protocol
            this.eventsProducer = eventsProducer
            // Disabled due ACP 0.24.0 vs Koog ACP beta binary mismatch in default handlers.
            this.setDefaultNotifications = false
        }
    }
}

private class KoogEchoAgentSession(
    override val sessionId: SessionId,
    private val protocol: Protocol
) : AgentSession {
    override suspend fun prompt(
        content: List<ContentBlock>,
        _meta: JsonElement?
    ): Flow<Event> = channelFlow {
        val userText = content
            .filterIsInstance<ContentBlock.Text>()
            .joinToString(" ") { it.text }
            .ifBlank { "(empty prompt)" }

        val koogAgent = buildKoogAcpEnabledAgent(sessionId, protocol, this)
        try {
            val echoedByKoog = koogAgent.run(userText)
            send(
                Event.SessionUpdateEvent(
                    SessionUpdate.AgentMessageChunk(
                        ContentBlock.Text("ACP server received: $echoedByKoog")
                    )
                )
            )
            send(Event.PromptResponseEvent(PromptResponse(StopReason.END_TURN)))
        } finally {
            koogAgent.close()
        }
    }
}

private class KoogEchoAgentSupport(
    private val protocol: Protocol
) : AgentSupport {
    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo = AgentInfo(
        protocolVersion = LATEST_PROTOCOL_VERSION,
        capabilities = AgentCapabilities()
    )

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
        val id = SessionId("session-${System.currentTimeMillis()}")
        return KoogEchoAgentSession(id, protocol)
    }

    override suspend fun loadSession(
        sessionId: SessionId,
        sessionParameters: SessionCreationParameters
    ): AgentSession = KoogEchoAgentSession(sessionId, protocol)
}

fun main() {
    val server = embeddedServer(CIO, port = 8080) {
        install(WebSockets)
        routing {
            acpProtocolOnServerWebSocket(path = "/acp", protocolOptions = ProtocolOptions()) { protocol: Protocol ->
                Agent(protocol = protocol, agentSupport = KoogEchoAgentSupport(protocol))
                protocol.start()
            }
        }
    }

    println("Koog ACP WebSocket server listening at ws://localhost:8080/acp")
    server.start(wait = true)
}
