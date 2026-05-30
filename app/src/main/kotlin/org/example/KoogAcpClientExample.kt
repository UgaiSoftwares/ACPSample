package org.example

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeDoNothing
import ai.koog.agents.features.acp.toKoogMessage
import ai.koog.agents.features.tracing.feature.TraceFeatureConfig
import ai.koog.agents.features.tracing.feature.Tracing
import ai.koog.agents.features.tracing.writer.TraceFeatureMessageFileWriter
import ai.koog.agents.features.tracing.writer.TraceFeatureMessageLogWriter
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.utils.time.KoogClock
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
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
import java.nio.file.Path
import org.slf4j.LoggerFactory

private class KoogLoggingSessionOps : ClientSessionOperations {
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
        if (notification is SessionUpdate.AgentMessageChunk) {
            println("[Koog notify] ${listOf(notification.content).toKoogText()}")
        }
    }
}

private fun List<ContentBlock>.toKoogText(): String = toKoogMessage(KoogClock.System).textContent()

private suspend fun promptAcpServer(session: ClientSession, prompt: String): String {
    val output = StringBuilder()
    session.prompt(listOf(ContentBlock.Text(prompt))).collect { event ->
        when (event) {
            is Event.SessionUpdateEvent -> {
                val update = event.update
                if (update is SessionUpdate.AgentMessageChunk) {
                    output.append(listOf(update.content).toKoogText())
                }
            }
            is Event.PromptResponseEvent -> println("[ACP bridge] Stop reason: ${event.response.stopReason}")
        }
    }
    return output.toString().ifBlank { "(empty ACP response)" }
}

private fun configureTracing(config: TraceFeatureConfig) {
    val traceLogger = LoggerFactory.getLogger("koog.tracing")
    val tracePath = Path.of(System.getenv("KOOG_TRACE_FILE") ?: "/tmp/koog-acp-trace.log")

    config.addMessageProcessor(TraceFeatureMessageLogWriter.create(traceLogger))
    config.addMessageProcessor(TraceFeatureMessageFileWriter.create(tracePath))
}

private fun buildGraphAgent(acpCall: suspend (String) -> String): AIAgent<String, String> {
    val textGraphStrategy = strategy<String, String>("acp-client-graph") {
        val nodeAcpBridge by node<String, String>("acp-server-bridge") { input ->
            acpCall(input)
        }
        val nodePostProcess by nodeDoNothing<String>("post-process")

        edge(nodeStart.forwardTo(nodeAcpBridge))
        edge(nodeAcpBridge.forwardTo(nodePostProcess))
        edge(nodePostProcess.forwardTo(nodeFinish))
    }

    val openAiApiKey = System.getenv("OPENAI_API_KEY")
        ?: error("OPENAI_API_KEY environment variable is required.")

    val promptExecutor = PromptExecutor.builder()
        .openAI(openAiApiKey)
        .build()

    val config = AIAgentConfig.withSystemPrompt(
        "You transform text in a deterministic way.",
        OpenAIModels.Chat.GPT4o,
        "en",
        8
    )

    return AIAgent(
        promptExecutor = promptExecutor,
        agentConfig = config,
        strategy = textGraphStrategy
    ) {
        install(Tracing) {
            configureTracing(this)
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
    val client = Client(protocol)
    protocol.start()

    client.initialize(
        ClientInfo(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            capabilities = ClientCapabilities(
                fs = FileSystemCapability(readTextFile = true, writeTextFile = true)
            )
        )
    )

    val session = client.newSession(
        SessionCreationParameters(cwd = "/tmp", mcpServers = emptyList()),
        ClientOperationsFactory { _, _ -> KoogLoggingSessionOps() }
    )

    val graphAgent = buildGraphAgent { prompt ->
        promptAcpServer(session, prompt)
    }

    val result = graphAgent.run("[Graph] Hello from Koog ACP client node. Please echo this.")
    println("[Koog graph result] $result")

    graphAgent.close()
    protocol.close()
    httpClient.close()
}
