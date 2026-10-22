package pl.krktransit.schedule.adapter.outbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class DownloadWorkspaceTest {
    @TempDir Path temporary;

    @Test
    void reclaimsAbandonedDownloadsWithoutRemovingOtherFilesOrFollowingLinks() throws IOException {
        Path abandoned;
        try (var previous = new DownloadWorkspace(temporary)) {
            abandoned = previous.createTemporary();
        }
        Path directory = abandoned.getParent();
        Path foreign = Files.writeString(temporary.resolve("gtfs-unowned.zip"), "keep outside workspace");
        Path note = Files.writeString(directory.resolve("notes.txt"), "keep unrelated file");
        Path link = Files.createSymbolicLink(directory.resolve("gtfs-link.zip"), foreign);
        Path nested = Files.createDirectory(directory.resolve("gtfs-directory.zip"));

        try (var restarted = new DownloadWorkspace(temporary)) {
            assertThat(abandoned).doesNotExist();
            assertThat(foreign).hasContent("keep outside workspace");
            assertThat(note).hasContent("keep unrelated file");
            assertThat(Files.isSymbolicLink(link)).isTrue();
            assertThat(nested).isDirectory();
            assertThat(restarted.createTemporary()).isRegularFile();
        }
    }

    @Test
    void refusesToCleanAWorkspaceThatIsStillInUse() throws IOException {
        try (var active = new DownloadWorkspace(temporary)) {
            Path inUse = Files.writeString(active.createTemporary(), "active import");

            assertThatThrownBy(() -> new DownloadWorkspace(temporary))
                    .isInstanceOf(IOException.class).hasMessageContaining("exclusive");

            assertThat(inUse).hasContent("active import");
        }
    }

    @Test
    @Timeout(20)
    void releasesOwnershipAfterAProcessCrashAndReclaimsItsDownload() throws Exception {
        Path ready = temporary.resolve("ready");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), CrashedDownload.class.getName(),
                temporary.toString(), ready.toString())
                .redirectErrorStream(true).redirectOutput(temporary.resolve("child.log").toFile()).start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> Files.exists(ready));
            Path download = Path.of(Files.readString(ready));
            assertThatThrownBy(() -> new DownloadWorkspace(temporary)).isInstanceOf(IOException.class);
            assertThat(download).isRegularFile();

            process.destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();

            try (var restarted = new DownloadWorkspace(temporary)) {
                assertThat(download).doesNotExist();
                assertThat(restarted.createTemporary()).isRegularFile();
            }
        } finally {
            process.destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void refusesASymbolicLinkInsteadOfItsOwnedDirectory() throws IOException {
        Path foreign = Files.createDirectory(temporary.resolve("foreign"));
        Path file = Files.writeString(foreign.resolve("gtfs-keep.zip"), "keep");
        Files.createSymbolicLink(temporary.resolve(DownloadWorkspace.DIRECTORY_NAME), foreign);

        assertThatThrownBy(() -> new DownloadWorkspace(temporary))
                .isInstanceOf(IOException.class).hasMessageContaining("symbolic link");

        assertThat(file).hasContent("keep");
    }

    public static final class CrashedDownload {
        public static void main(String[] arguments) throws Exception {
            try (var workspace = new DownloadWorkspace(Path.of(arguments[0]))) {
                Files.writeString(Path.of(arguments[1]), workspace.createTemporary().toString());
                new CountDownLatch(1).await();
            }
        }
    }
}
