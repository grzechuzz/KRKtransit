package pl.krktransit.schedule.adapter.outbound.gtfscsv;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.apache.commons.io.input.ChecksumInputStream;
import pl.krktransit.schedule.application.importing.ImportFailure;
import pl.krktransit.schedule.application.port.FeedParser;
import pl.krktransit.schedule.application.port.ScheduleWriter;
import pl.krktransit.schedule.domain.feed.FeedId;

public final class StreamingGtfsParser implements FeedParser {
    private static final long DEFAULT_MAX_BYTES = 8L * 1024 * 1024 * 1024;
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader().setSkipHeaderRecord(true).setAllowMissingColumnNames(true)
            .setDuplicateHeaderMode(DuplicateHeaderMode.ALLOW_ALL).get();
    private final long maxUncompressedBytes;

    public StreamingGtfsParser() { this(DEFAULT_MAX_BYTES); }

    public StreamingGtfsParser(long maxUncompressedBytes) {
        if (maxUncompressedBytes <= 0) throw new IllegalArgumentException("maxUncompressedBytes must be positive");
        this.maxUncompressedBytes = maxUncompressedBytes;
    }

    @Override
    public void parse(FeedId feed, Path path, ScheduleWriter writer) {
        var budget = new ReadBudget(maxUncompressedBytes);
        try (var zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            requireFiles(zip);
            writeTables(feed, zip, budget, writer);
        } catch (IOException | UncheckedIOException failure) {
            throw new ImportFailure(ImportFailure.Kind.VALIDATION, "Cannot parse GTFS ZIP: " + failure.getMessage(), failure);
        }
    }

    private void writeTables(FeedId feed, ZipFile zip, ReadBudget budget, ScheduleWriter writer) {
        writer.writeRoutes(rows -> readTable(zip, GtfsTable.ROUTES, feed, budget, GtfsRow::route, rows));
        writer.writeStops(rows -> readTable(zip, GtfsTable.STOPS, feed, budget, GtfsRow::stop, rows));
        writer.writeTrips(rows -> readTable(zip, GtfsTable.TRIPS, feed, budget, GtfsRow::trip, rows));
        writer.writeStopTimes(rows -> readTable(zip, GtfsTable.STOP_TIMES, feed, budget, GtfsRow::stopTime, rows));
        writer.writeShapePoints(rows -> readTable(zip, GtfsTable.SHAPES, feed, budget, GtfsRow::shapePoint, rows));
    }

    private void requireFiles(ZipFile zip) {
        for (var table : GtfsTable.values()) {
            ZipEntry entry = zip.getEntry(table.filename());
            if (entry == null || entry.isDirectory()) {
                throw new ImportFailure(ImportFailure.Kind.VALIDATION, "Missing GTFS file: " + table.filename());
            }
        }
    }

    private <T> void readTable(ZipFile zip, GtfsTable table, FeedId feed, ReadBudget budget,
                              Function<GtfsRow, T> mapper, Consumer<? super T> receiver) {
        checkCancellation();
        try (var reader = reader(zip, table, budget); var csv = FORMAT.parse(reader)) {
            requireHeaders(table, csv);
            long rows = emitRows(csv, table, feed, mapper, receiver);
            if (rows == 0 && table.requiresRows()) {
                throw new ImportFailure(ImportFailure.Kind.VALIDATION, table.filename() + " must contain data rows");
            }
        } catch (IOException | UncheckedIOException failure) {
            throw new ImportFailure(ImportFailure.Kind.VALIDATION,
                    "Cannot parse " + table.filename() + ": " + failure.getMessage(), failure);
        }
    }

    private <T> long emitRows(CSVParser csv, GtfsTable table, FeedId feed,
                             Function<GtfsRow, T> mapper, Consumer<? super T> receiver) {
        long count = 0;
        for (var record : csv) {
            checkCancellation();
            receiver.accept(mapper.apply(new GtfsRow(feed, table, record)));
            count++;
        }
        return count;
    }

    private void requireHeaders(GtfsTable table, CSVParser csv) {
        for (String column : table.headers()) {
            if (!csv.getHeaderMap().containsKey(column)) {
                throw new ImportFailure(ImportFailure.Kind.VALIDATION,
                        table.filename() + " is missing required column: " + column);
            }
        }
    }

    private Reader reader(ZipFile zip, GtfsTable table, ReadBudget budget) throws IOException {
        var entry = zip.getEntry(table.filename());
        var verified = ChecksumInputStream.builder().setInputStream(zip.getInputStream(entry))
                .setChecksum(new CRC32()).setExpectedChecksumValue(entry.getCrc()).get();
        var input = new PushbackInputStream(budget.wrap(verified), 3);
        byte[] prefix = input.readNBytes(3);
        if (!isBom(prefix)) input.unread(prefix);
        return new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder());
    }

    private boolean isBom(byte[] prefix) {
        return prefix.length == 3 && prefix[0] == (byte) 0xef && prefix[1] == (byte) 0xbb && prefix[2] == (byte) 0xbf;
    }

    private static void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) {
            throw new ImportFailure(ImportFailure.Kind.CANCELLED, "GTFS parsing interrupted");
        }
    }

    private static final class ReadBudget {
        private long remaining;

        private ReadBudget(long limit) { remaining = limit; }

        private void consume(int bytes) throws IOException {
            if (bytes <= 0) return;
            remaining -= bytes;
            if (remaining < 0) throw new IOException("GTFS uncompressed byte limit exceeded");
        }

        private InputStream wrap(InputStream input) {
            return new FilterInputStream(input) {
                @Override public int read() throws IOException {
                    int value = in.read();
                    consume(value < 0 ? 0 : 1);
                    return value;
                }

                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    int count = in.read(bytes, offset, length);
                    consume(count);
                    return count;
                }
            };
        }
    }
}
