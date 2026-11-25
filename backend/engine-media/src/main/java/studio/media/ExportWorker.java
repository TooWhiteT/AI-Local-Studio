package studio.media;

import java.io.DataInputStream;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/** Owns one bounded FFmpeg command independently of the Studio process. */
public final class ExportWorker {
    private static FileChannel channel(Path dir) throws Exception {
        return FileChannel.open(dir.resolve("worker.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }
    public static boolean busy(Path dir) throws Exception {
        try (var channel = channel(dir); var lock = channel.tryLock()) { return lock == null; }
    }
    static void mark(Path dir, String name) throws Exception {
        if (!Files.exists(dir.resolve(name))) Files.createFile(dir.resolve(name));
    }
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        try (var channel = channel(dir); var lock = channel.tryLock()) {
            if (lock == null || Files.exists(dir.resolve("started")) || Files.exists(dir.resolve("cancel"))) return;
            var command = new ArrayList<String>();
            command.add("ffmpeg");
            try (var input = new DataInputStream(Files.newInputStream(dir.resolve("args.bin")))) {
                int size = input.readInt();
                if (size < 1 || size > 20000) throw new IllegalArgumentException();
                for (int i = 0; i < size; i++) command.add(input.readUTF());
            }
            mark(dir, "started");
            try {
                var child = new ProcessBuilder(command).directory(dir.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(dir.resolve("ffmpeg.log").toFile()).start();
                try {
                    long deadline = System.nanoTime() + TimeUnit.HOURS.toNanos(2);
                    while (!child.waitFor(200, TimeUnit.MILLISECONDS)) {
                        if (Files.exists(dir.resolve("cancel")) || System.nanoTime() >= deadline) {
                            child.destroy();
                            if (!child.waitFor(3, TimeUnit.SECONDS)) child.destroyForcibly().waitFor();
                            break;
                        }
                    }
                    if (Files.exists(dir.resolve("cancel"))) return;
                    if (child.exitValue() != 0) { mark(dir, "failed"); return; }
                    Files.move(dir.resolve("output.part.mp4"), dir.resolve("output.mp4"), StandardCopyOption.ATOMIC_MOVE);
                } finally { if (child.isAlive()) child.destroyForcibly().waitFor(); }
            } catch (Exception failure) { mark(dir, "failed"); }
            finally { Files.deleteIfExists(dir.resolve("output.part.mp4")); }
        }
    }
}
