package pl.krktransit.schedule.domain.schedule;

public record ScheduleHash(String value) {
    public ScheduleHash {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("hash must contain 64 lowercase hexadecimal SHA-256 characters");
        }
    }
}
