package com.moveinsync.roster.domain.reconcile;

import com.moveinsync.roster.domain.model.HrProfile;
import com.moveinsync.roster.domain.model.RecordHasher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Partitions export rows into one temp file per bucket while computing per-bucket digests.
 * Heap use: one small write buffer per bucket plus the digest arrays. Disk use: O(rows).
 */
public final class BucketSpill implements Closeable {

    private static final long REJECTED_SALT = 0x5bd1e9955bd1e995L;

    private final Path dir;
    private final RecordHasher hasher;
    private final DataOutputStream[] writers;
    private final BucketDigests digests;

    public BucketSpill(Path dir, RecordHasher hasher) {
        this.dir = dir;
        this.hasher = hasher;
        this.writers = new DataOutputStream[hasher.bucketCount()];
        this.digests = new BucketDigests(hasher.bucketCount());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public BucketDigests digests() {
        return digests;
    }

    public void add(SpilledRow row) {
        int b = hasher.bucket(row.externalId());
        // A rejected row contributes a value that never matches the store, forcing that bucket
        // to be inspected, where the row is treated as "present".
        long contribution = row.rejected() ? RecordHasher.hash(row.externalId()) ^ REJECTED_SALT : row.digest();
        digests.add(b, contribution);
        try {
            DataOutputStream out = writer(b);
            out.writeInt(row.index());
            out.writeUTF(row.externalId());
            out.writeBoolean(row.rejected());
            if (!row.rejected()) {
                out.writeLong(row.version());
                out.writeLong(row.digest());
                HrProfile hr = row.hr();
                writeNullable(out, hr.name());
                writeNullable(out, hr.email());
                writeNullable(out, hr.phone());
                writeNullable(out, hr.managerExternalId());
                writeNullable(out, hr.officeCode());
                writeNullable(out, hr.shiftCode());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Must be called before {@link #readBucket}. */
    public void finishWriting() {
        for (int i = 0; i < writers.length; i++) {
            if (writers[i] != null) {
                try {
                    writers[i].close();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                writers[i] = null;
            }
        }
    }

    public List<SpilledRow> readBucket(int bucket) {
        Path f = file(bucket);
        List<SpilledRow> rows = new ArrayList<>();
        if (!Files.exists(f)) {
            return rows;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
            while (true) {
                int index;
                try {
                    index = in.readInt();
                } catch (EOFException eof) {
                    break;
                }
                String ext = in.readUTF();
                boolean rejected = in.readBoolean();
                if (rejected) {
                    rows.add(SpilledRow.rejected(index, ext));
                    continue;
                }
                long version = in.readLong();
                long digest = in.readLong();
                HrProfile hr = new HrProfile(readNullable(in), readNullable(in), readNullable(in),
                        readNullable(in), readNullable(in), readNullable(in));
                rows.add(new SpilledRow(index, ext, version, digest, false, hr));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    private DataOutputStream writer(int bucket) throws IOException {
        if (writers[bucket] == null) {
            writers[bucket] = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file(bucket)), 4096));
        }
        return writers[bucket];
    }

    private Path file(int bucket) {
        return dir.resolve("bucket-" + bucket + ".bin");
    }

    private static void writeNullable(DataOutputStream out, String s) throws IOException {
        out.writeBoolean(s != null);
        if (s != null) {
            out.writeUTF(s);
        }
    }

    private static String readNullable(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    @Override
    public void close() {
        finishWriting();
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
