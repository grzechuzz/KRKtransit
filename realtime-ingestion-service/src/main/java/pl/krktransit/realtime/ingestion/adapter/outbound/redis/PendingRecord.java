package pl.krktransit.realtime.ingestion.adapter.outbound.redis;

record PendingRecord(StreamTarget target, byte[] payload) {
    long size() {
        return (long) target.key().length + payload.length;
    }
}
