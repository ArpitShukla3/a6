package com.moveinsync.roster.domain.reconcile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Streams the nightly export one line at a time (constant memory). Format:
 * <pre>
 * #ROSTER_EXPORT,as_of=2026-10-29T00:00:00Z,expected_rows=200000
 * external_id,source_version,name,email,phone,manager_external_id,office_code,shift_code
 * E1,7,...
 * #END,rows=200000
 * </pre>
 */
public final class SnapshotReader {

    public static final List<String> COLUMNS = List.of(
            "external_id", "source_version", "name", "email", "phone", "manager_external_id", "office_code", "shift_code");

    @FunctionalInterface
    public interface RowVisitor {
        /** @param fields parsed fields, or null if the line is malformed */
        void row(int index, List<String> fields);
    }

    public SnapshotScan read(BufferedReader in, RowVisitor visitor) {
        try {
            SnapshotHeader header = parseHeader(in.readLine());
            String columns = in.readLine();
            if (columns == null || !CsvLineParser.parse(columns.trim()).equals(COLUMNS)) {
                throw new SnapshotFormatException("SNAPSHOT_COLUMNS_INVALID");
            }
            int rows = 0;
            Integer trailer = null;
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (line.startsWith("#END")) {
                    trailer = parseTrailer(line);
                    break;
                }
                List<String> fields = CsvLineParser.parse(line);
                visitor.row(rows, fields != null && fields.size() == COLUMNS.size() ? fields : null);
                rows++;
            }
            return new SnapshotScan(header, rows, trailer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static SnapshotHeader parseHeader(String line) {
        if (line == null || !line.startsWith("#ROSTER_EXPORT")) {
            throw new SnapshotFormatException("SNAPSHOT_HEADER_MISSING");
        }
        Instant asOf = null;
        Integer expected = null;
        for (String part : line.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            try {
                switch (kv[0].trim()) {
                    case "as_of" -> asOf = Instant.parse(kv[1].trim());
                    case "expected_rows" -> expected = Integer.parseInt(kv[1].trim());
                    default -> { }
                }
            } catch (DateTimeParseException | NumberFormatException e) {
                throw new SnapshotFormatException("SNAPSHOT_HEADER_INVALID");
            }
        }
        if (asOf == null) {
            throw new SnapshotFormatException("SNAPSHOT_HEADER_INVALID");
        }
        return new SnapshotHeader(asOf, expected);
    }

    private static Integer parseTrailer(String line) {
        int i = line.indexOf("rows=");
        if (i < 0) {
            return null;
        }
        try {
            return Integer.parseInt(line.substring(i + 5).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
