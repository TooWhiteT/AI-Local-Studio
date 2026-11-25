package studio.infra

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*
import studio.application.Media
import studio.core.*
import studio.engine.InvalidOutput

class VideoMediaTest {
    @Test fun croppedPngUsesThePinnedOriginAndPreservesItsContents() {
        val root = Files.createTempDirectory("studio-frame")
        try {
            val lib = LocalMedia(root)
            val project = uid()
            val image = BufferedImage(6, 2, BufferedImage.TYPE_INT_RGB)
            for (x in 0 until 6) for (y in 0 until 2)
                image.setRGB(x, y, when (x) { 2, 3 -> Color.GREEN.rgb; else -> Color.RED.rgb })
            val bytes = ByteArrayOutputStream().apply { ImageIO.write(image, "png", this) }.toByteArray()
            val source = lib.save(project, uid(), 0, bytes)
            assertContentEquals(bytes, lib.readFirstFrame(project, source, 6, 2))
            val cropped = lib.readFirstFrame(project, source, 2, 2)
            val decoded = ImageIO.read(ByteArrayInputStream(cropped))
            assertEquals(2, decoded.width)
            assertEquals(2, decoded.height)
            assertEquals(Color.GREEN.rgb, decoded.getRGB(0, 1))
            assertEquals(source.sha256, sha256(Files.readAllBytes(lib.resolve(source.relativePath))))
            assertFailsWith<InvalidOutput> { lib.readFirstFrame(uid(), source, 2, 2) }
            assertFailsWith<InvalidOutput> { lib.readFirstFrame(project, source.copy(sha256 = "changed"), 2, 2) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun ffprobeCountsDecodedFramesAndRejectsTruncatedOrStillOutputs() {
        val root = Files.createTempDirectory("studio-video")
        try {
            val lib = LocalMedia(root)
            val project = uid()
            val attempt = uid()
            val sample = Files.readAllBytes(Path.of("backend/mock-comfy/src/main/resources/mock-shot.mp4"))
            fun save(bytes: ByteArray): Media = lib.saveVideo(project, attempt, 0, bytes)
            val video = save(sample)
            assertEquals(96, video.width)
            assertEquals(64, video.height)
            assertEquals(72L, video.frameCount)
            assertEquals(3000L, video.durationMs)
            assertEquals(24.0, video.fps)
            assertEquals("video/mp4", video.mime)
            assertEquals(video, save(sample))
            assertFailsWith<InvalidOutput> { lib.saveVideo(project, uid(), 0, sample.copyOf(sample.size / 2)) }
            val oneFrame = javaClass.getResourceAsStream("/single-frame.mp4")!!.use { it.readBytes() }
            val error = assertFailsWith<InvalidOutput> { lib.saveVideo(project, uid(), 0, oneFrame) }
            assertContains(error.message.orEmpty(), "帧数")
            assertContentEquals(sample, Files.readAllBytes(lib.resolve(video.relativePath)))
        } finally { root.toFile().deleteRecursively() }
    }
}
