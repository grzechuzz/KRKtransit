package pl.krktransit.livevehicles.domain;

public record StreamId(long milliseconds, long sequence) implements Comparable<StreamId> {
    public static StreamId parse(String value) {
        var parts = value.split("-", -1);
        requireComponents(parts);
        return new StreamId(Long.parseUnsignedLong(parts[0]), Long.parseUnsignedLong(parts[1]));
    }

    private static void requireComponents(String[] parts) {
        if (parts.length != 2) throw new IllegalArgumentException("Invalid stream ID");
    }

    @Override
    public int compareTo(StreamId other) {
        int time = Long.compareUnsigned(milliseconds, other.milliseconds);
        return time == 0 ? Long.compareUnsigned(sequence, other.sequence) : time;
    }

    @Override
    public String toString() { return Long.toUnsignedString(milliseconds) + "-" + Long.toUnsignedString(sequence); }
}
