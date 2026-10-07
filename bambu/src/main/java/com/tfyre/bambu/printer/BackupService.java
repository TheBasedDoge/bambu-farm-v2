package com.tfyre.bambu.printer;

import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One zip a day of the app's data files - queues, mappings, stock, order tracking, history, settings. Everything
 * the app knows lives in those JSON files and nowhere else, so a single bad write (or a mistaken click) could
 * otherwise cost months of mappings.
 * <p>
 * Checks every few hours and writes the day's zip if there isn't one yet, rather than firing at a fixed time: a
 * machine that was off at 3 a.m. still gets its backup when it comes back.
 * <p>
 * What goes in: every {@code bambu-*.json} file (and their {@code .pre-*} / {@code .bak} siblings) in the data
 * folder. That includes the Etsy/eBay token files. {@code .env} and the logs are left out.
 */
@ApplicationScoped
public class BackupService {

    private static final String PREFIX = "bambu-data-";

    @Inject
    BambuConfig config;
    @Inject
    NotificationService notificationService;

    private Path dataDir() {
        final Path parent = Path.of(config.maintenanceFile()).toAbsolutePath().getParent();
        return parent != null ? parent : Path.of(".").toAbsolutePath();
    }

    private Path backupDir() {
        return config.backup().dir().filter(d -> !d.isBlank()).map(Path::of).orElseGet(() -> dataDir().resolve("backups"));
    }

    @Scheduled(every = "4h", delayed = "5m", identity = "data-backup")
    void scheduled() {
        if (config.backup().keep() <= 0) {
            return;
        }
        try {
            final Path target = backupDir().resolve(PREFIX + LocalDate.now() + ".zip");
            if (Files.exists(target)) {
                return;
            }
            final int files = write(target);
            prune();
            Log.infof("BackupService: wrote %s (%d file(s), %d KB)", target, files, Files.size(target) / 1024);
        } catch (IOException | RuntimeException ex) {
            Log.errorf(ex, "BackupService: backup failed: %s", ex.getMessage());
            notificationService.notifyEvent("backup_failed", "Backup",
                    "The data backup could not be written to %s: %s".formatted(backupDir(), ex.getMessage()));
        }
    }

    private int write(final Path target) throws IOException {
        Files.createDirectories(target.getParent());
        final List<Path> sources;
        try (Stream<Path> list = Files.list(dataDir())) {
            sources = list.filter(Files::isRegularFile)
                    .filter(p -> {
                        final String name = p.getFileName().toString();
                        return name.startsWith("bambu-") && name.contains(".json");
                    })
                    .sorted()
                    .toList();
        }
        if (sources.isEmpty()) {
            throw new IOException("no bambu-*.json files found in " + dataDir());
        }
        // Written beside the target and moved into place, so a half-written zip is never mistaken for a backup.
        final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (final Path source : sources) {
                zip.putNextEntry(new ZipEntry(source.getFileName().toString()));
                Files.copy(source, zip);
                zip.closeEntry();
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return sources.size();
    }

    /** Deletes the oldest zips beyond the number to keep. The date in the name sorts them. */
    private void prune() throws IOException {
        final List<Path> zips;
        try (Stream<Path> list = Files.list(backupDir())) {
            zips = list.filter(p -> {
                final String name = p.getFileName().toString();
                return name.startsWith(PREFIX) && name.endsWith(".zip");
            }).sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
        }
        for (int i = config.backup().keep(); i < zips.size(); i++) {
            Files.deleteIfExists(zips.get(i));
            Log.infof("BackupService: removed old backup %s", zips.get(i).getFileName());
        }
    }
}
