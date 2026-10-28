package pl.krktransit.realtime.ingestion.adapter.outbound.gtfsrt;

import com.google.protobuf.UnknownFieldSet;
import java.util.List;

final class RawEnumCode {
    private RawEnumCode() {}

    static Integer of(boolean known, int knownNumber, UnknownFieldSet unknownFields, int fieldNumber) {
        if (known) {
            return knownNumber;
        }
        return unknownCode(unknownFields, fieldNumber);
    }

    private static Integer unknownCode(UnknownFieldSet unknownFields, int fieldNumber) {
        if (!unknownFields.hasField(fieldNumber)) {
            return null;
        }
        List<Long> values = unknownFields.getField(fieldNumber).getVarintList();
        return values.isEmpty() ? null : values.getLast().intValue();
    }
}
