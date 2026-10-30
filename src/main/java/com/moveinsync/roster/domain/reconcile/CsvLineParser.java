package com.moveinsync.roster.domain.reconcile;

import java.util.ArrayList;
import java.util.List;

/** Minimal RFC 4180 line parser (quoted fields, doubled quotes). No embedded newlines. */
public final class CsvLineParser {

    private CsvLineParser() {
    }

    /** Fields of the line, or null if quoting is malformed. */
    public static List<String> parse(String line) {
        List<String> out = new ArrayList<>(10);
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean fieldWasQuoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                if (cur.length() > 0) {
                    return null;
                }
                quoted = true;
                fieldWasQuoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
                fieldWasQuoted = false;
            } else {
                if (fieldWasQuoted) {
                    return null;
                }
                cur.append(c);
            }
        }
        if (quoted) {
            return null;
        }
        out.add(cur.toString());
        return out;
    }
}
