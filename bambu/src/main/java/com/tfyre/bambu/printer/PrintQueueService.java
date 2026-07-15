package com.tfyre.bambu.printer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import com.tfyre.ftp.BambuFtp;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Shutdown;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.commons.net.ftp.FTPFile;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * Per printer print queue. Entries reference files in the batch print library; starting the next job uploads the file to the printer SD card (skipped when
 * already present with the same size) and sends the print command. Jobs are only ever started by an explicit user action so a finished print is never printed
 * over.
 */
@ApplicationScoped
public class PrintQueueService {

    /**
     * @param orderRef the marketplace order this job fulfills, or {@code null} for jobs queued outside the
     *                 order flow (Batch Print page) and entries persisted before this field existed
     */
    public record QueueEntry(BambuPrinter.CommandPPF command, double grams, GcodeSource source, OrderRef orderRef) {

        /** Convenience constructor for the common case: a file from the batch print library. */
        public QueueEntry(final BambuPrinter.CommandPPF command, final double grams) {
            this(command, grams, GcodeSource.LIBRARY, null);
        }

        /** Backward-compatible constructor for entries with no order linkage. */
        public QueueEntry(final BambuPrinter.CommandPPF command, final double grams, final GcodeSource source) {
            this(command, grams, source, null);
        }

    }

    @Inject
    BambuConfig config;
    @Inject
    ObjectMapper mapper;
    @Inject
    Instance<BambuFtp> clientInstance;
    @Inject
    ManagedExecutor executor;
    @Inject
    PrintHistoryService historyService;
    @Inject
    BambuPrinters printers;
    @Inject
    NotificationService notificationService;
    /** Lazy to avoid an eager circular reference (AutoQueueService injects this service). */
    @Inject
    jakarta.enterprise.inject.Instance<AutoQueueService> autoQueueInstance;

    private final Map<String, List<QueueEntry>> data = new HashMap<>();
    private boolean dirty;

    /** The last queue-started job per printer, so a failure can be matched back to its entry for auto-requeue. */
    private record StartedJob(QueueEntry original, String effectiveFile) {
    }

    private final Map<String, StartedJob> lastStarted = new java.util.concurrent.ConcurrentHashMap<>();
    /** printer|file|orderId → retry attempts, so a failing job is only auto-requeued once. */
    private final Map<String, Integer> retryCounts = new java.util.concurrent.ConcurrentHashMap<>();

    private Path getPath() {
        return Path.of(config.queueFile());
    }

    @PostConstruct
    synchronized void load() {
        final Path path = getPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            final Map<String, List<QueueEntry>> loaded = mapper.readValue(path.toFile(), new TypeReference<Map<String, List<QueueEntry>>>() {
            });
            data.putAll(loaded);
            Log.infof("PrintQueueService: loaded queues for %d printer(s) from %s", loaded.size(), path);
        } catch (IOException ex) {
            Log.errorf(ex, "PrintQueueService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private synchronized void save() {
        if (!dirty) {
            return;
        }
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(), data);
            dirty = false;
        } catch (IOException ex) {
            Log.errorf(ex, "PrintQueueService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    @Shutdown
    void onShutdown() {
        save();
    }

    public synchronized List<QueueEntry> getQueue(final String printer) {
        return List.copyOf(data.getOrDefault(printer, List.of()));
    }

    public synchronized int size(final String printer) {
        return data.getOrDefault(printer, List.of()).size();
    }

    public synchronized Optional<QueueEntry> peek(final String printer) {
        final List<QueueEntry> queue = data.getOrDefault(printer, List.of());
        return queue.isEmpty() ? Optional.empty() : Optional.of(queue.get(0));
    }

    public synchronized void add(final String printer, final QueueEntry entry) {
        data.computeIfAbsent(printer, k -> new ArrayList<>()).add(entry);
        dirty = true;
        save();
    }

    /**
     * Removes the given entry (matched by identity, so safe against the queue having shifted since the UI
     * rendered it - a render-time index could silently delete a different job). No-op if the entry is no
     * longer queued (e.g. already started or removed elsewhere).
     */
    public synchronized void removeEntry(final String printer, final QueueEntry entry) {
        final List<QueueEntry> queue = data.get(printer);
        if (queue == null) {
            return;
        }
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i) == entry) {
                queue.remove(i);
                dirty = true;
                save();
                return;
            }
        }
    }

    /** Puts an entry at the FRONT of a printer's queue (used by auto-requeue so the retry goes next). */
    public synchronized void requeueFront(final String printer, final QueueEntry entry) {
        data.computeIfAbsent(printer, k -> new ArrayList<>()).add(0, entry);
        dirty = true;
        save();
    }

    /** Moves an existing entry (matched by identity) to the front of its printer's queue. */
    public synchronized void moveToFront(final String printer, final QueueEntry entry) {
        final List<QueueEntry> queue = data.get(printer);
        if (queue == null) {
            return;
        }
        for (int i = 1; i < queue.size(); i++) {
            if (queue.get(i) == entry) {
                queue.remove(i);
                queue.add(0, entry);
                dirty = true;
                save();
                return;
            }
        }
    }

    /**
     * Called by {@link PrintHistoryService} when any print ends. When the ended print was queue-started, this
     * matches it back to its queue entry and - if auto-requeue is enabled - puts a failed job back at the
     * front of the queue for ONE retry (auto-start's bed-clear gate still applies before it runs again).
     * A second failure of the same job stops and alerts instead of looping filament into the bin.
     */
    public void onJobEnded(final PrintHistoryService.PrintJob job) {
        final StartedJob started = lastStarted.get(job.printer());
        if (started == null || !started.effectiveFile().equals(job.file())) {
            return;
        }
        lastStarted.remove(job.printer());
        final String key = "%s|%s|%s".formatted(job.printer(), started.original().command().filename(),
                started.original().orderRef() == null ? "" : started.original().orderRef().orderId());
        if ("Finished".equals(job.result())) {
            retryCounts.remove(key);
            return;
        }
        if (!autoQueueInstance.get().isAutoRequeueEnabled()) {
            return;
        }
        final int attempts = retryCounts.getOrDefault(key, 0);
        if (attempts >= 1) {
            retryCounts.remove(key);
            Log.warnf("PrintQueueService: %s: %s failed again after a retry - giving up", job.printer(), job.file());
            notificationService.notifyEvent("auto_requeue", job.printer(),
                    "%s failed AGAIN after an automatic retry - not requeueing, needs a human (%s)"
                            .formatted(job.file(), job.result()));
            return;
        }
        retryCounts.put(key, attempts + 1);
        requeueFront(job.printer(), started.original());
        Log.infof("PrintQueueService: %s: %s ended %s - auto-requeued at the front for one retry", job.printer(), job.file(), job.result());
        notificationService.notifyEvent("auto_requeue", job.printer(),
                "Print %s: %s - automatically requeued for one retry (bed-clear gate still applies)"
                        .formatted(job.result().toLowerCase(), job.file()));
    }

    private synchronized void removeFirst(final String printer, final QueueEntry entry) {
        final List<QueueEntry> queue = data.get(printer);
        if (queue != null && !queue.isEmpty() && queue.get(0) == entry) {
            queue.remove(0);
            dirty = true;
        }
        save();
    }

    /** SD-card system folders that can't contain user project files - skipped by the subfolder scan. */
    private static final Set<String> SYSTEM_DIRS = Set.of("image", "ipcam", "logger", "recorder", "timelapse", "cache", "verify_job");

    /**
     * Ensures {@code filename} is available on the printer's SD card, uploading it only when necessary, and
     * returns the SD path the print should actually use. An identical file (same name and size) already
     * present at the root <b>or in a first-level subfolder</b> (e.g. a hand-organized {@code _Audi/part.3mf})
     * is reused in place instead of re-uploading a duplicate copy to the root.
     */
    private String uploadIfNeeded(final BambuPrinters.PrinterDetail detail, final String filename, final File file) throws IOException {
        final BambuFtp client = clientInstance.get().setup(detail, (total, bytes, stream) -> {
        });
        try {
            client.doConnect();
            if (!client.doLogin()) {
                throw new IOException("FTP login failed");
            }
            final FTPFile[] rootEntries = client.listFiles();
            final Optional<FTPFile> atRoot = Stream.of(rootEntries)
                    .filter(f -> f.isFile() && filename.equals(f.getName()))
                    .findAny();
            if (atRoot.isPresent() && atRoot.get().getSize() == file.length()) {
                Log.debugf("%s: queue file already on SD card: %s", detail.name(), filename);
                return filename;
            }
            // Not at the root - check first-level subfolders before uploading a duplicate
            for (final FTPFile dir : rootEntries) {
                if (!dir.isDirectory() || dir.getName().startsWith(".") || SYSTEM_DIRS.contains(dir.getName().toLowerCase())) {
                    continue;
                }
                final Optional<FTPFile> inDir = Stream.of(client.listFiles(dir.getName()))
                        .filter(f -> f.isFile() && filename.equals(f.getName()) && f.getSize() == file.length())
                        .findAny();
                if (inDir.isPresent()) {
                    final String path = dir.getName() + "/" + filename;
                    Log.infof("%s: queue file already on SD card in a subfolder, printing from there: %s", detail.name(), path);
                    return path;
                }
            }
            try (final FileInputStream stream = new FileInputStream(file)) {
                if (!client.doUpload(filename, stream)) {
                    throw new IOException("upload failed");
                }
            }
            return filename;
        } finally {
            try {
                client.doClose();
            } catch (IOException ex) {
                Log.error(ex.getMessage(), ex);
            }
        }
    }

    /**
     * Starts the next queued job on the printer. Caller is responsible for user confirmation (bed cleared). Callbacks run on a worker thread - wrap with
     * ui.access().
     */
    public void startNext(final String printerName, final Runnable onSuccess, final Consumer<String> onError) {
        startNext(printerName, "queue", onSuccess, onError);
    }

    /**
     * @param trigger recorded into print history so runs can be filtered by how they started -
     *                "queue" (manual Start Next) or "auto-start" (AI-gated auto-start)
     */
    public synchronized void startNext(final String printerName, final String trigger, final Runnable onSuccess, final Consumer<String> onError) {
        // synchronized closes the check-then-act race between isBlocked() below and setBlocked(true):
        // two sessions clicking Start Next at the same moment would otherwise both pass the check and
        // double-start the same job. The second caller now sees blocked=true and gets a clean error.
        final Optional<BambuPrinters.PrinterDetail> oDetail = printers.getPrinterDetail(printerName);
        if (oDetail.isEmpty()) {
            onError.accept("%s: unknown printer".formatted(printerName));
            return;
        }
        final BambuPrinters.PrinterDetail detail = oDetail.get();
        final Optional<QueueEntry> oEntry = peek(printerName);
        if (oEntry.isEmpty()) {
            onError.accept("%s: queue is empty".formatted(printerName));
            return;
        }
        if (!detail.printer().getGCodeState().isReady() || detail.printer().isBlocked()) {
            onError.accept("%s: printer is not ready".formatted(printerName));
            return;
        }
        final QueueEntry entry = oEntry.get();
        detail.printer().setBlocked(true);
        executor.submit(() -> {
            try {
                BambuPrinter.CommandPPF command = entry.command();
                if (entry.source() == GcodeSource.LIBRARY) {
                    final File file = Path.of(config.batchPrint().library()).resolve(command.filename()).toFile();
                    if (!file.isFile()) {
                        onError.accept("%s: not in library: %s".formatted(printerName, command.filename()));
                        return;
                    }
                    final String sdPath = uploadIfNeeded(detail, command.filename(), file);
                    if (!sdPath.equals(command.filename())) {
                        // Identical file found in an SD subfolder - print from there instead of the root copy
                        command = new BambuPrinter.CommandPPF(sdPath, command.plateId(), command.useAms(),
                                command.timelapse(), command.bedLevelling(), command.flowCalibration(),
                                command.vibrationCalibration(), command.amsMapping());
                    }
                }
                // SD_CARD entries reference a file already resident on the printer's SD card at this path -
                // nothing to upload, just send the print command directly.
                historyService.registerExpectedWeight(printerName, command.filename(), entry.grams(), trigger, entry.orderRef());
                lastStarted.put(printerName, new StartedJob(entry, command.filename()));
                detail.printer().commandPrintProjectFile(command);
                removeFirst(printerName, entry);
                Log.infof("PrintQueueService: %s: started %s (plate %d)", printerName, command.filename(), command.plateId());
                onSuccess.run();
            } catch (Throwable ex) {
                Log.errorf(ex, "PrintQueueService: %s: %s", printerName, ex.getMessage());
                onError.accept("%s: %s".formatted(printerName, ex.getMessage()));
            } finally {
                detail.printer().setBlocked(false);
            }
        });
    }

}
