package studio.llm;

import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** One bounded HTTP exchange that can finish while Studio is restarting. */
public final class CompletionWorker {
    private static FileChannel lockFile(Path dir) throws Exception {
        return FileChannel.open(dir.resolve("worker.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    public static boolean busy(Path dir) throws Exception {
        try (var channel = lockFile(dir); var lock = channel.tryLock()) { return lock == null; }
    }

    public static boolean withdrawIfUnsent(Path dir) throws Exception {
        try (var channel = lockFile(dir); var lock = channel.tryLock()) {
            if (lock == null || Files.exists(dir.resolve("sent"))) return false;
            if (!Files.exists(dir.resolve("withdrawn"))) Files.createFile(dir.resolve("withdrawn"));
            return true;
        }
    }

    private static void publish(Path dir, String name, byte[] body) throws Exception {
        Path temp = Files.createTempFile(dir, "reply-", ".part");
        try {
            Files.write(temp, body);
            Files.move(temp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        try (var channel = lockFile(dir); var lock = channel.tryLock()) {
            if (lock == null || Files.exists(dir.resolve("sent")) || Files.exists(dir.resolve("withdrawn"))) return;
            byte[] request = Files.readAllBytes(dir.resolve("request.json"));
            var c = (HttpURLConnection) URI.create(args[1]).toURL().openConnection(Proxy.NO_PROXY);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(5000);
            c.setReadTimeout(600000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            String key = System.getenv("STUDIO_LLM_API_KEY");
            if (key != null && !key.isBlank()) c.setRequestProperty("Authorization", "Bearer " + key);
            c.setDoOutput(true);
            // Fixed-length streaming prevents transparent retries of an ambiguous POST.
            c.setFixedLengthStreamingMode(request.length);
            Files.createFile(dir.resolve("sent"));
            try {
                try (var output = c.getOutputStream()) { output.write(request); }
                int status = c.getResponseCode();
                if (status != 200) {
                    publish(dir, "status", Integer.toString(status).getBytes(StandardCharsets.US_ASCII));
                    return;
                }
                try (var input = c.getInputStream()) {
                    byte[] reply = input.readNBytes(1048577);
                    if (reply.length > 1048576) publish(dir, "invalid", new byte[0]);
                    else publish(dir, "response.json", reply);
                }
            } finally { c.disconnect(); }
        }
    }
}
