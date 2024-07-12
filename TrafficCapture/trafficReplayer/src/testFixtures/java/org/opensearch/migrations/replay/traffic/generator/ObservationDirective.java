package org.opensearch.migrations.replay.traffic.generator;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class ObservationDirective {
    public final OffloaderCommandType offloaderCommandType;
    public final int size;

    public static ObservationDirective read(int i) {
        return new ObservationDirective(OffloaderCommandType.Read, i);
    }

    public static ObservationDirective eom() {
        return new ObservationDirective(OffloaderCommandType.EndOfMessage, 0);
    }

    public static ObservationDirective cancelOffload() {
        return new ObservationDirective(OffloaderCommandType.DropRequest, 0);
    }

    public static ObservationDirective write(int i) {
        return new ObservationDirective(OffloaderCommandType.Write, i);
    }

    public static ObservationDirective flush() {
        return new ObservationDirective(OffloaderCommandType.Flush, 0);
    }

    @Override
    public String toString() {
        return "(" + offloaderCommandType + ":" + size + ")";
    }
}
