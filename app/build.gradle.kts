plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.guava)
    implementation("com.agentclientprotocol:acp:0.24.0")
    implementation("com.agentclientprotocol:acp-ktor-client:0.24.0")
    implementation("com.agentclientprotocol:acp-ktor-server:0.24.0")
    implementation("io.ktor:ktor-client-cio:3.5.0")
    implementation("io.ktor:ktor-server-cio:3.5.0")
    implementation("ai.koog:agents-features-acp:1.0.0-beta-preview7")
    implementation("ai.koog:agents-features-trace:1.0.0")
    implementation("ai.koog:http-client-ktor:1.0.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.18")
}

testing {
    suites {
        val test = getByName<JvmTestSuite>("test")
        test.useKotlinTest("2.3.20")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    // Default entrypoint runs the client example.
    mainClass = "org.example.AcpClientExampleKt"
}

tasks.register<JavaExec>("runAcpServer") {
    group = "application"
    description = "Run ACP server example over WebSocket"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.example.AcpServerExampleKt")
}

tasks.register<JavaExec>("runAcpClient") {
    group = "application"
    description = "Run ACP client example over WebSocket (connects to ws://localhost:8080/acp)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.example.AcpClientExampleKt")
}

tasks.register<JavaExec>("runKoogAcpClient") {
    group = "application"
    description = "Run ACP client example using Koog message converters (connects to ws://localhost:8080/acp)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.example.KoogAcpClientExampleKt")
}

tasks.register<JavaExec>("runKoogAcpServer") {
    group = "application"
    description = "Run ACP server example implemented with Koog + AcpAgent over WebSocket"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.example.KoogAcpServerExampleKt")
}
