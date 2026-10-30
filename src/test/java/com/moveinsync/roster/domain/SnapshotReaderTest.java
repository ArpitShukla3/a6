package com.moveinsync.roster.domain;

import com.moveinsync.roster.domain.reconcile.CsvLineParser;
import com.moveinsync.roster.domain.reconcile.SnapshotFormatException;
import com.moveinsync.roster.domain.reconcile.SnapshotReader;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotReaderTest {

    private static final String HEADER = "#ROSTER_EXPORT,as_of=2026-10-29T00:00:00Z,expected_rows=2\n"
            + "external_id,source_version,name,email,phone,manager_external_id,office_code,shift_code\n";

    private final SnapshotReader reader = new SnapshotReader();

    @Test
    void completeFile() {
        List<List<String>> rows = new ArrayList<>();
        var scan = reader.read(in(HEADER + "E1,1,\"Rao, Asha\",a@b.co,,M1,BLR-01,S1\nE2,1,B,b@b.co,,,BLR-01,\n#END,rows=2\n"),
                (i, f) -> rows.add(f));
        assertThat(scan.truncated()).isFalse();
        assertThat(rows.get(0).get(2)).isEqualTo("Rao, Asha");
    }

    @Test
    void missingTrailerMeansTruncated() {
        var scan = reader.read(in(HEADER + "E1,1,A,a@b.co,,,BLR-01,S1\n"), (i, f) -> { });
        assertThat(scan.truncated()).isTrue();
        assertThat(scan.rowsRead()).isEqualTo(1);
    }

    @Test
    void rowCountMismatchMeansTruncated() {
        var scan = reader.read(in(HEADER + "E1,1,A,a@b.co,,,BLR-01,S1\n#END,rows=1\n"), (i, f) -> { });
        assertThat(scan.truncated()).isTrue(); // header promised 2
    }

    @Test
    void malformedLineIsPassedAsNull() {
        List<List<String>> rows = new ArrayList<>();
        reader.read(in(HEADER + "E1,\"broken\n#END,rows=1\n"), (i, f) -> rows.add(f));
        assertThat(rows).containsExactly((List<String>) null);
    }

    @Test
    void missingHeaderRejectsFile() {
        assertThatThrownBy(() -> reader.read(in("external_id,x\n"), (i, f) -> { }))
                .isInstanceOf(SnapshotFormatException.class).hasMessage("SNAPSHOT_HEADER_MISSING");
    }

    @Test
    void csvQuoting() {
        assertThat(CsvLineParser.parse("a,\"b \"\"c\"\"\",,d")).containsExactly("a", "b \"c\"", "", "d");
        assertThat(CsvLineParser.parse("a,\"b")).isNull();
    }

    private static BufferedReader in(String s) {
        return new BufferedReader(new StringReader(s));
    }
}
