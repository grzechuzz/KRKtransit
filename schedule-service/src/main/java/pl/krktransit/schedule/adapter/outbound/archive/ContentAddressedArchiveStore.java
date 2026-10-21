package pl.krktransit.schedule.adapter.outbound.archive;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import pl.krktransit.schedule.application.importing.DownloadedFeed;
import pl.krktransit.schedule.application.importing.ImportFailure;
import pl.krktransit.schedule.application.port.ArchiveStore;

public final class ContentAddressedArchiveStore implements ArchiveStore {
    private static final System.Logger LOG = System.getLogger(ContentAddressedArchiveStore.class.getName());
    private final Path directory;

    public ContentAddressedArchiveStore(Path directory) { this.directory = directory; }

    @Override
    public void keep(DownloadedFeed download) {
        Path target = directory.resolve(download.hash().value() + ".zip");
        if (Files.isRegularFile(target)) return;
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".archive-", ".tmp");
            copy(download.path(), temporary);
            publish(temporary, target);
        } catch (IOException failure) {
            throw new ImportFailure(ImportFailure.Kind.ARCHIVE, "Cannot archive ZIP " + download.hash().value(), failure);
        } finally {
            cleanup(temporary);
        }
    }

    private void copy(Path source, Path target) throws IOException {
        try (var input = Files.newInputStream(source); var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancellation();
                output.write(buffer, 0, count);
            }
        }
    }

    private void publish(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target);
        } catch (FileAlreadyExistsException alreadyArchived) {
            // Concurrent importers may archive identical content; the existing copy wins.
            if (!Files.isRegularFile(target)) throw alreadyArchived;
        }
    }

    private void cleanup(Path temporary) {
        if (temporary == null) return;
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.WARNING, "Cannot remove temporary archive {0}: {1}", temporary, failure.getMessage());
        }
    }

    private void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) {
            throw new ImportFailure(ImportFailure.Kind.CANCELLED, "ZIP archiving interrupted");
        }
    }
}
