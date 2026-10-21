package pl.krktransit.schedule.adapter.outbound.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.krktransit.schedule.application.importing.DownloadedFeed;
import pl.krktransit.schedule.application.importing.ImportFailure;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;

class ContentAddressedArchiveStoreTest {
    private static final ScheduleHash HASH = new ScheduleHash("a".repeat(64));
    @TempDir Path directory;

    @Test
    void archivesByHashAndRetainsDownloadedFile() throws IOException {
        var download = Files.writeString(directory.resolve("download.zip"), "zip bytes");
        var archives = directory.resolve("archives");

        new ContentAddressedArchiveStore(archives).keep(new DownloadedFeed(download, HASH));

        assertThat(archives.resolve(HASH.value() + ".zip")).hasContent("zip bytes");
        assertThat(download).hasContent("zip bytes");
        try (var entries = Files.list(archives)) {
            assertThat(entries).hasSize(1);
        }
    }

    @Test
    void reusesExistingArchiveWithoutOverwritingItOrReadingDownload() throws IOException {
        var archives = Files.createDirectory(directory.resolve("archives"));
        var archive = Files.writeString(archives.resolve(HASH.value() + ".zip"), "original");

        new ContentAddressedArchiveStore(archives).keep(new DownloadedFeed(directory.resolve("missing.zip"), HASH));

        assertThat(archive).hasContent("original");
    }

    @Test
    void cleansIncompleteArchiveWhenInputFails() throws IOException {
        var archives = directory.resolve("archives");

        assertThatThrownBy(() -> new ContentAddressedArchiveStore(archives)
                .keep(new DownloadedFeed(directory.resolve("missing.zip"), HASH)))
                .isInstanceOfSatisfying(ImportFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ImportFailure.Kind.ARCHIVE));
        try (var entries = Files.list(archives)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void rejectsDirectoryAtArchivePathInsteadOfReportingSuccess() throws IOException {
        var archives = Files.createDirectory(directory.resolve("archives"));
        var archivePath = Files.createDirectory(archives.resolve(HASH.value() + ".zip"));
        var download = Files.writeString(directory.resolve("download.zip"), "zip bytes");

        assertThatThrownBy(() -> new ContentAddressedArchiveStore(archives).keep(new DownloadedFeed(download, HASH)))
                .isInstanceOfSatisfying(ImportFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ImportFailure.Kind.ARCHIVE));
        assertThat(archivePath).isDirectory();
        assertThat(download).hasContent("zip bytes");
        try (var entries = Files.list(archives)) { assertThat(entries).containsExactly(archivePath); }
    }
}
