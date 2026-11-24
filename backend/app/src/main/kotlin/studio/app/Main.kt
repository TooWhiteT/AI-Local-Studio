package studio.app

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import studio.application.*
import studio.comfy.ComfyClient
import studio.engine.TextEngine
import studio.llm.*
import studio.infra.*
import studio.scheduler.GpuScheduler
import studio.scheduler.WriterLoadBudget

data class Settings(val storage: Path, val packages: Path, val mode: String, val address: String,
    val frontend: Path = Path.of("frontend/dist"), val pollMs: Long = 250, val scopedInterrupt: Boolean = mode == "mock",
    val acceptanceOrigin: String? = null, val llmMode: String = if (mode == "mock") "mock" else "disabled",
    val llmUrl: String = "http://127.0.0.1:8081", val llmModel: String? = null, val writerBudget: WriterLoadBudget = WriterLoadBudget()) {
    companion object {
        fun environment(): Settings {
            val mode = System.getenv("STUDIO_ENGINE") ?: "mock"
            require(mode in listOf("comfy", "mock"))
            val storage = Path.of(System.getenv("STUDIO_STORAGE") ?: "storage")
            val configuredPackages = System.getenv("STUDIO_WORKFLOWS")?.takeIf(String::isNotBlank)
            val packages = configuredPackages?.let(Path::of) ?: if (mode == "comfy") Path.of("workflows") else storage.resolve("mock-workflows")
            if (mode == "mock" && configuredPackages == null) {
                val dest = packages.resolve("mock-image/1")
                Files.createDirectories(dest)
                for (name in listOf("manifest.json", "workflow.json")) {
                    if (!dest.resolve(name).exists()) Files.copy(Path.of("fixtures/mock-workflows/mock-image/1", name), dest.resolve(name))
                }
            }
            val port = if (mode == "comfy") 8188 else 18188
            val acceptance = System.getenv("STUDIO_ACCEPTANCE_COMFY_ORIGIN")?.takeIf(String::isNotBlank)
            val llmUrl = System.getenv("STUDIO_LLM_URL")?.takeIf(String::isNotBlank)
            val llmMode = System.getenv("STUDIO_LLM_MODE") ?: if (llmUrl != null) "llama" else if (mode == "mock") "mock" else "disabled"
            require(llmMode in setOf("llama","mock","disabled"))
            return Settings(storage, packages, mode, System.getenv("COMFY_URL") ?: acceptance ?: "http://127.0.0.1:$port",
                scopedInterrupt = mode == "mock" || System.getenv("COMFY_SCOPED_INTERRUPT") == "true", acceptanceOrigin = acceptance,
                llmMode = llmMode, llmUrl = llmUrl ?: "http://127.0.0.1:8081", llmModel = System.getenv("STUDIO_LLM_MODEL")?.takeIf(String::isNotBlank),
                writerBudget = WriterLoadBudget(System.getenv("STUDIO_LLM_LOAD_VRAM_MIB")?.toLong() ?: 0,
                    System.getenv("STUDIO_LLM_LOAD_RAM_MIB")?.toLong() ?: 0))
        }
    }
}
class Studio(val settings: Settings) : AutoCloseable {
    private val lock = StudioLock(settings.storage, settings.mode, java.net.URI(settings.address).port, settings.llmMode == "llama")
    val database = StudioDb(settings.storage.resolve("studio.db"))
    val catalog: Catalog = SqlCatalog(database)
    val tasks: Tasks = SqlTasks(database)
    val writing: WritingStore = SqlWriting(database)
    val writer: TextEngine? = when (settings.llmMode) {
        "llama" -> LlamaEngine(settings.llmUrl,settings.llmModel,settings.storage)
        "mock" -> MockTextEngine()
        "disabled" -> null
        else -> error("未知编剧引擎")
    }
    val workflows: WorkflowStore = SqlWorkflows(database)
    val media = LocalMedia(settings.storage)
    val engine = ComfyClient(settings.address, settings.mode, settings.scopedInterrupt, acceptanceOrigin = settings.acceptanceOrigin)
    val registry = WorkflowRegistry(workflows, WorkflowFiles(settings.packages), engine)
    val scheduler = GpuScheduler(tasks, registry, media, engine, settings.pollMs, writer, settings.writerBudget)
    fun start() = runBlocking {
        media.clean(database)
        registry.refresh()
        scheduler.start()
    }
    override fun close() {
        runBlocking { scheduler.stop() }
        engine.close()
        lock.close()
    }
}
fun Application.studioRoutes(studio: Studio) {
    installHttp()
    routing {
        route("/api/v1") {
            catalogRoutes(studio.catalog)
            writingRoutes(studio)
            productionRoutes(studio)
        }
        staticFiles("/", studio.settings.frontend.toFile(), "index.html")
    }
}
fun studioServer(studio: Studio, listenPort: Int): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val connections = DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true)
    return embeddedServer(Netty, configure = {
        connector { host = "127.0.0.1"; port = listenPort }
        shareWorkGroup = true
        channelPipelineConfig = { connections.add(channel()) }
    }) {
        studioRoutes(studio)
        monitor.subscribe(ApplicationStopPreparing) { connections.close().awaitUninterruptibly() }
        monitor.subscribe(ApplicationStopping) { studio.close() }
    }
}
fun main() {
    val settings = Settings.environment()
    val studio = Studio(settings)
    try {
        studio.start()
        studioServer(studio, System.getenv("STUDIO_PORT")?.toInt() ?: 8080).start(wait = true)
    } finally { studio.close() }
}
