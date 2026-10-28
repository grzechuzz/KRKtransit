package pl.krktransit.realtime.ingestion.adapter.outbound.redis;

record StreamTarget(byte[] key, long maxLength) {
}
