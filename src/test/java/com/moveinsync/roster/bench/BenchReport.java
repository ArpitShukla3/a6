package com.moveinsync.roster.bench;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

final class BenchReport {

    static void resetPeaks() {
        System.gc();
        ManagementFactory.getMemoryPoolMXBeans().forEach(MemoryPoolMXBean::resetPeakUsage);
    }

    /** Sum of peak usage of heap pools since the last reset (upper bound of live + garbage). */
    static long peakHeapMb() {
        long sum = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP && p.getPeakUsage() != null) {
                sum += p.getPeakUsage().getUsed();
            }
        }
        return sum / (1024 * 1024);
    }

    static String machine() {
        String cpu = "unknown";
        try {
            cpu = Files.readAllLines(Path.of("/proc/cpuinfo")).stream().filter(l -> l.startsWith("model name"))
                    .findFirst().map(l -> l.substring(l.indexOf(':') + 1).trim()).orElse(cpu);
        } catch (IOException ignored) {
        }
        return "CPU: " + cpu + ", cores: " + Runtime.getRuntime().availableProcessors()
                + ", max heap: " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB"
                + ", JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version")
                + ", OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version");
    }

    static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    static void write(String section) {
        System.out.println(section);
        try {
            Path out = Path.of("target", "bench-results.md");
            Files.createDirectories(out.getParent());
            Files.writeString(out, section + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private BenchReport() {
    }
}
