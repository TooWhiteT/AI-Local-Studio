package studio.application

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import studio.core.*
import studio.engine.*

class VideoWorkflowTest {
    private fun pkg(id: String): Package {
        val dir = Path.of("workflows", id, "1.0.0")
        return Package(json.decodeFromString(Files.readString(dir.resolve("manifest.json"))),
            json.parseToJsonElement(Files.readString(dir.resolve("workflow.json"))).jsonObject, dir.toString())
    }
    // The target machine's object_info, limited to used sockets and installed models. Uploaded files start empty.
    private val schema = json.parseToJsonElement(javaClass.getResource("/h3-input-schema.json")!!.readText()).jsonObject
    private val frame = "studio-" + "a".repeat(64) + ".png"
    private val last = "studio-" + "b".repeat(64) + ".png"

    @Test fun validatedExportsBindOnlyTheFramesDeclaredByTheirCapability() {
        for (id in listOf("minimax-h3-i2v", "minimax-h3-fl2v", "minimax-h3-r2v")) {
            val pkg = pkg(id)
            val m = pkg.manifest
            validatePackage(pkg, "comfy", schema)
            val firstName = m.firstFrame?.let { frame }
            val lastName = m.lastFrame?.let { last }
            val refName = m.referenceImage?.let { frame }
            val bound = bindFrames(pkg.graph, m, firstName, lastName, refName)
            for ((input, name) in listOf(m.firstFrame to firstName, m.lastFrame to lastName, m.referenceImage to refName)) {
                if (input != null) assertEquals(JsonPrimitive(name), bound[input.node]!!.jsonObject["inputs"]!!.jsonObject[input.input])
            }
            assertFailsWith<StudioError> { bindFrames(pkg.graph, m, "../outside.png", lastName, refName) }
            assertFailsWith<StudioError> { bindFrames(pkg.graph, m, null, null, null) }
            val changed = m.copy(models = m.models.map { it.copy(name = "missing") })
            assertFailsWith<StudioError> { validatePackage(pkg.copy(manifest = changed), "comfy", schema) }
        }
    }
    @Test fun dynamicSocketsMustMatchTheSelectedComboAndAutogrowDeclarations() {
        val pkg = pkg("minimax-h3-r2v")
        fun changed(nodeId: String, from: String, to: String): Package {
            val node = pkg.graph.getValue(nodeId).jsonObject
            val fields = node.getValue("inputs").jsonObject
            val changedNode = JsonObject(node + ("inputs" to JsonObject(fields - from + (to to fields.getValue(from)))))
            return pkg.copy(graph = JsonObject(pkg.graph + (nodeId to changedNode)))
        }
        assertFailsWith<StudioError> { validatePackage(changed("136", "ref_images.ref_image_0", "ref_images.ref_image_9"), "comfy", schema) }
        assertFailsWith<StudioError> { validatePackage(changed("92", "format.codec", "format.arbitrary"), "comfy", schema) }
    }
    @Test fun originalImageWorkflowHasItsPublishedHash() {
        assertEquals("267f55980330fea9e316ab190a3ec54ebc0e830734c3e533d5d91c64bd56aa76", pkg("z-image-turbo").hash)
    }
}
