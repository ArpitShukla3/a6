package com.moveinsync.roster.domain;

import com.moveinsync.roster.domain.reconcile.BrakeInput;
import com.moveinsync.roster.domain.reconcile.SafetyBrake;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafetyBrakeTest {

    private final SafetyBrake brake = SafetyBrake.standard();

    @ParameterizedTest(name = "{0} missing of 10000 at threshold {1} -> tripped={2}")
    @CsvSource({
            "2999, 0.30, false",
            "3000, 0.30, true",   // exactly 30% trips
            "3001, 0.30, true",
            "1000, 0.10, true",   // configurable threshold
            "999,  0.10, false",
            "0,    0.30, false"})
    void missingRatioThreshold(int missing, double threshold, boolean tripped) {
        var in = new BrakeInput(10_000, missing, 10_000 - missing, 0, false, threshold, 0.05);
        assertThat(brake.evaluate(in).tripped()).isEqualTo(tripped);
    }

    @Test
    void briefExampleFileCutAt120kOf200k() {
        var in = new BrakeInput(200_000, 80_000, 120_000, 0, true, 0.30, 0.05);
        var d = brake.evaluate(in);
        assertThat(d.tripped()).isTrue();
        assertThat(d.reasons()).containsExactly("MISSING_RATIO", "TRUNCATED_FILE");
        assertThat(d.missingRatio()).isEqualTo(0.4);
    }

    @Test
    void truncatedFileTripsEvenWithFewMissing() {
        assertThat(brake.evaluate(new BrakeInput(100, 1, 99, 0, true, 0.30, 0.05)).reasons()).containsExactly("TRUNCATED_FILE");
    }

    @Test
    void manyRejectedRowsTrip() {
        assertThat(brake.evaluate(new BrakeInput(100, 0, 100, 5, false, 0.30, 0.05)).reasons()).containsExactly("REJECTED_RATIO");
        assertThat(brake.evaluate(new BrakeInput(100, 0, 100, 4, false, 0.30, 0.05)).tripped()).isFalse();
    }

    @Test
    void firstLoadIntoEmptyStoreDoesNotTrip() {
        assertThat(brake.evaluate(new BrakeInput(0, 0, 5000, 0, false, 0.30, 0.05)).tripped()).isFalse();
    }
}
