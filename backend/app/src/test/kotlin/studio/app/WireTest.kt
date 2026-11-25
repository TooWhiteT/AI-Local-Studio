package studio.app

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.test.*
import studio.application.*
import studio.comfy.ComfyClient
import studio.core.*
import studio.engine.*
import studio.infra.WorkflowFiles
import studio.mock.MockComfy
import studio.mock.mockServer

class WireTest {
    @Test fun exportedZImagePackageBindsOnlyPublishedInputs() {
        val pkg = WorkflowFiles(Path.of("workflows")).packages().single {
            it.manifest.id == "z-image-turbo" && it.manifest.version == "1.0.0"
        }
        val m = pkg.manifest
        assertEquals("comfy", m.mode)
        assertEquals(listOf(ImageOutput("9")), m.outputs)
        assertEquals(JsonPrimitive("SaveImage"), pkg.graph.getValue("9").jsonObject["class_type"])
        val reachable = mutableSetOf<String>()
        fun visit(id: String) {
            if (!reachable.add(id)) return
            pkg.graph.getValue(id).jsonObject.getValue("inputs").jsonObject.values.filterIsInstance<JsonArray>()
                .forEach { visit(it[0].jsonPrimitive.content) }
        }
        visit(m.outputs.single().node)
        assertEquals(pkg.graph.keys, reachable)
        val bound = bind(pkg.graph, m, buildJsonObject { put("seed", 101); put("prompt", "晨雾"); put("negativePrompt", "文字") })
        fun input(id: String, key: String) = bound.getValue(id).jsonObject.getValue("inputs").jsonObject.getValue(key)
        assertEquals(JsonPrimitive("晨雾"), input("67", "text"))
        assertEquals(JsonPrimitive("文字"), input("71", "text"))
        assertEquals(JsonPrimitive(101), input("70", "seed"))
        for (model in m.models) assertEquals(JsonPrimitive(model.name), input(model.node, model.input))
        assertEquals(pkg.graph, bind(pkg.graph, m, buildJsonObject {}))
        assertFailsWith<StudioError> { bind(pkg.graph, m, buildJsonObject { put("unet_name", "unknown") }) }
    }

    @Test fun realAcceptancePinsOnePrivateOrigin() {
        val lan = "http://192.168.31.154:8188"
        ComfyClient(lan, "comfy", acceptanceOrigin = "$lan/").use { assertEquals("comfy@$lan", it.identity) }
        assertFailsWith<IllegalArgumentException> { ComfyClient(lan, "comfy") }
        assertFailsWith<IllegalArgumentException> { ComfyClient(lan, "mock", acceptanceOrigin = lan) }
        assertFailsWith<IllegalArgumentException> { ComfyClient("http://192.168.31.155:8188", "comfy", acceptanceOrigin = lan) }
        for (bad in listOf("$lan/path", "$lan?x=1", "$lan#fragment", "http://user@192.168.31.154:8188",
            "http://example.org:8188", "http://8.8.8.8:8188", "http://169.254.169.254:80",
            "http://192.168.31.154", "http://192.168.31.154:65536", "http://192.168.031.154:8188")) {
            assertFailsWith<IllegalArgumentException>(bad) { ComfyClient(bad, "comfy", acceptanceOrigin = bad) }
        }
    }

    @Test fun mockRestWebsocketAndBindings() = runBlocking<Unit> {
        val mock = MockComfy(70)
        val server = mockServer(mock, 0).start()
        val engine = ComfyClient("http://127.0.0.1:${server.engine.resolvedConnectors().single().port}", "mock")
        try {
            val pkg = WorkflowFiles(Path.of("fixtures/mock-workflows")).packages().single { it.manifest.id == "mock-image" }
            val info = engine.objectInfo()
            validatePackage(pkg, "mock", info)
            assertFailsWith<StudioError> { validatePackage(pkg, "comfy", info) }
            assertFailsWith<StudioError> { validatePackage(pkg, "mock", JsonObject(emptyMap())) }
            assertFailsWith<StudioError> { bind(pkg.graph, pkg.manifest, buildJsonObject { put("seed", -1) }) }
            assertFailsWith<StudioError> { bind(pkg.graph, pkg.manifest, buildJsonObject { put("other", 3) }) }
            assertEquals("mock-wire-1", engine.info().version)
            val attempt = uid()
            engine.listen(attempt).use { ws ->
                val id = engine.submit(attempt, bind(pkg.graph, pkg.manifest, buildJsonObject { put("seed", 12) }))
                val p = withTimeout(5000) { var item = ws.latest(); while (item == null) { delay(15); item = ws.latest() }; item }
                assertEquals(id, p.promptId)
                withTimeout(5000) { while (engine.history(id, pkg.manifest.outputs) == null) delay(20) }
                val result = engine.history(id, pkg.manifest.outputs) as History.Success
                assertEquals(0x89, engine.download(result.files.single()).first().toInt() and 255)
                assertEquals(id, engine.findPrompt(attempt))
                assertTrue(engine.queue().isEmpty())
                assertFailsWith<InvalidOutput> { engine.download(OutputRef("../escape", "", "output")) }
            }
        } finally { engine.close(); mock.close(); server.stop(0, 1000) }
    }
}
