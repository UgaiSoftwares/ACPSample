# ACPSample

Kotlin/JVM sample project for experimenting with the Agent Client Protocol (ACP) and Koog integrations.

The repository currently contains one `app` module with:

- a generated hello-world entry point
- a plain ACP WebSocket echo server and client
- Koog-based ACP client and server examples

## Requirements

- Java 21
- Gradle wrapper provided in the repository
- A local WebSocket server listening on `ws://localhost:8080/acp` when running the client examples
- `OPENAI_API_KEY` for the Koog client example
- An Ollama-compatible backend available for the Koog server example

## Project Layout

- `app/src/main/kotlin/org/example/App.kt` - generated hello-world application
- `app/src/main/kotlin/org/example/AcpServerExample.kt` - simple ACP echo server over WebSocket
- `app/src/main/kotlin/org/example/AcpClientExample.kt` - ACP client that connects to the server and prints streamed text
- `app/src/main/kotlin/org/example/KoogAcpClientExample.kt` - Koog graph agent that calls the ACP server
- `app/src/main/kotlin/org/example/KoogAcpServerExample.kt` - Koog-backed ACP server using `AcpAgent`

## Running

Use the Gradle wrapper from the repository root:

```bash
./gradlew :app:run
```

That runs `org.example.AcpClientExampleKt`, which expects the ACP echo server to be running on `ws://localhost:8080/acp`.

If you want the generated hello-world example instead, change the `application.mainClass` setting in `app/build.gradle.kts` to `org.example.AppKt`.

Run the plain ACP server in one terminal:

```bash
./gradlew :app:runAcpServer
```

Then run the plain ACP client in another terminal:

```bash
./gradlew :app:runAcpClient
```

The client initializes ACP with file-system capabilities, sends a prompt, and prints streamed text responses from the server.

## Koog Examples

The Koog client example expects the ACP server to be running first:

```bash
export OPENAI_API_KEY=your-key
./gradlew :app:runKoogAcpClient
```

It builds a Koog graph agent, forwards the prompt to the ACP server over WebSocket, and writes trace output to `/tmp/koog-acp-trace.log` by default. Set `KOOG_TRACE_FILE` to change the trace destination.

The Koog server example starts its own ACP WebSocket server:

```bash
./gradlew :app:runKoogAcpServer
```

It uses Koog's `AcpAgent` integration and an Ollama-backed prompt executor.

## Test

```bash
./gradlew :app:test
```

## Notes

- The ACP server examples listen on `ws://localhost:8080/acp`.
- The plain server returns `ACP server received: ...` and ends the turn after each prompt.
- The Koog client and server examples both use ACP sessions with file-system capabilities enabled for text file read/write.
