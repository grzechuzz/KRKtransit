package pl.krktransit.schedule.adapter.outbound.http;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class DownloadWorkspace implements AutoCloseable {
    static final String DIRECTORY_NAME = "krktransit-schedule-downloads";
    private static final System.Logger LOG = System.getLogger(DownloadWorkspace.class.getName());
    private final Path directory;
    private final FileChannel ownership;

    DownloadWorkspace(Path parent) throws IOException {
        directory = parent.resolve(DIRECTORY_NAME);
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Download workspace must be a directory, not a symbolic link");
        }
        ownership = acquireOwnership();
        removeAbandonedDownloads();
    }

    private FileChannel acquireOwnership() throws IOException {
        var channel = FileChannel.open(directory.resolve(".lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            if (channel.tryLock() == null) throw new IOException("Download workspace is already in use");
            return channel;
        } catch (IOException | OverlappingFileLockException failure) {
            channel.close();
            throw new IOException("Cannot acquire exclusive download workspace ownership", failure);
        }
    }

    private void removeAbandonedDownloads() {
        try (var files = Files.newDirectoryStream(directory, "gtfs-*.zip")) {
            for (Path file : files) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) remove(file);
            }
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.WARNING, "Cannot scan abandoned downloads in " + directory, failure);
        }
    }

    private void remove(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.WARNING, "Cannot remove abandoned download " + file, failure);
        }
    }

    Path createTemporary() throws IOException {
        return Files.createTempFile(directory, "gtfs-", ".zip");
    }

    @Override
    public void close() {
        try {
            ownership.close();
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.WARNING, "Cannot release download workspace " + directory, failure);
        }
    }
}
