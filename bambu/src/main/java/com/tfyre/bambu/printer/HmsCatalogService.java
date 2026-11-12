package com.tfyre.bambu.printer;

import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Keeps {@link HmsCodes} current: once a day, for each printer model in the farm, downloads Bambu's HMS / error
 * database (the one Bambu Studio uses) into {@code data/hms-cache/} and switches to it when it is newer than what
 * is loaded. Offline it keeps the cached or bundled copy - a failed download only logs.
 */
@ApplicationScoped
public class HmsCatalogService {

    private static final String URL = "https://e.bambulab.com/query.php?lang=en&d=%s";

    @Inject
    BambuConfig config;
    @Inject
    BambuPrinters printers;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private boolean cacheLoaded;

    @Scheduled(every = "24h", delayed = "1m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void refresh() {
        final Set<String> devs = new TreeSet<>();
        printers.getPrinters().forEach(p -> HmsCodes.deviceId(p.getModel()).ifPresent(devs::add));
        if (!cacheLoaded) {
            devs.forEach(this::loadCache);
            cacheLoaded = true;
        }
        devs.forEach(this::download);
    }

    private Path dir() {
        return Path.of(config.maintenanceFile()).toAbsolutePath().getParent().resolve("hms-cache");
    }

    private Path file(final String dev) {
        return dir().resolve("hms_%s.json".formatted(dev));
    }

    private void loadCache(final String dev) {
        final Path f = file(dev);
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            final HmsCodes.Db db = HmsCodes.parse(HmsCodes.mapper().readTree(f.toFile()));
            if (newer(db, dev)) {
                HmsCodes.install(dev, db);
                Log.infof("HmsCatalog: %s loaded from cache (version %s, %d HMS codes)", dev, db.ver(), db.hms().size());
            }
        } catch (IOException | RuntimeException ex) {
            Log.warnf("HmsCatalog: cached %s unreadable: %s", f, ex.getMessage());
        }
    }

    private void download(final String dev) {
        try {
            final HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(URL.formatted(dev)))
                    .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                Log.infof("HmsCatalog: download for %s answered HTTP %d - keeping version %s", dev, r.statusCode(), HmsCodes.version(dev));
                return;
            }
            final HmsCodes.Db db = HmsCodes.parse(HmsCodes.mapper().readTree(r.body()));
            if (db.hms().size() < 100) {
                Log.infof("HmsCatalog: download for %s looked empty (%d codes) - keeping version %s", dev, db.hms().size(), HmsCodes.version(dev));
                return;
            }
            if (!newer(db, dev)) {
                return;
            }
            Files.createDirectories(dir());
            final Path f = file(dev);
            final Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            HmsCodes.mapper().writeValue(tmp.toFile(), HmsCodes.toJson(db));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            HmsCodes.install(dev, db);
            Log.infof("HmsCatalog: %s updated to version %s (%d HMS codes, %d error codes)", dev, db.ver(), db.hms().size(), db.errors().size());
        } catch (IOException | RuntimeException ex) {
            Log.infof("HmsCatalog: download for %s failed (%s) - keeping version %s", dev, ex.getMessage(), HmsCodes.version(dev));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Versions are yyyyMMddHHmm stamps; an unknown one counts as older. */
    private static boolean newer(final HmsCodes.Db db, final String dev) {
        final String have = Optional.ofNullable(HmsCodes.version(dev)).orElse("");
        return db.ver().compareTo(have) > 0;
    }
}
