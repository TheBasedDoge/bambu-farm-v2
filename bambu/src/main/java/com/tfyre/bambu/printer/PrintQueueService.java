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

    public record QueueEntry(BambuPrinter.CommandPPF command, double grams, GcodeSource source) {

        /** Convenience constructor for the common case: a file from the batch print library. */
        public QueueEntry(final BambuPrinter.CommandPPF command, final double grams) {
            this(command, grams, GcodeSource.LIBRARY);
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

    private final Map<String, List<QueueEntry>> data = new HashMap<>();
    private boolean dirty;

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

    public synchronized void remove(final String printer, final int index) {
        final List<QueueEntry> queue = data.get(printer);
        if (queue == null || index < 0 || index >= queue.size()) {
            return;
        }
        queue.remove(index);
        dirty = true;
        save();
    }

    private synchronized void removeFirst(final String printer, final QueueEntry entry) {
        final List<QueueEntry> queue = data.get(printer);
        if (queue != null && !queue.isEmpty() && queue.get(0) == entry) {
            queue.remove(0);
            dirty = true;
        }
        save();
    }

    private void uploadIfNeeded(final BambuPrinters.PrinterDetail detail, final String filename, final File file) throws IOException {
        final BambuFtp client = clientInstance.get().setup(detail, (total, bytes, stream) -> {
        });
        try {
            client.doConnect();
            if (!client.doLogin()) {
                throw new IOException("FTP login failed");
            }
            final Optional<FTPFile> oFile = Stream.of(client.listFiles())
                    .filter(f -> f.isFile() && filename.equals(f.getName()))
                    .findAny();
            if (oFile.isPresent() && oFile.get().getSize() == file.length()) {
                Log.debugf("%s: queue file already on SD card: %s", detail.name(), filename);
                return;
            }
            try (final FileInputStream stream = new FileInputStream(file)) {
                if (!client.doUpload(filename, stream)) {
                    throw new IOException("upload failed");
                }
            }
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
                if (entry.source() == GcodeSource.LIBRARY) {
                    final File file = Path.of(config.batchPrint().library()).resolve(entry.command().filename()).toFile();
                    if (!file.isFile()) {
                        onError.accept("%s: not in library: %s".formatted(printerName, entry.command().filename()));
                        return;
                    }
                    uploadIfNeeded(detail, entry.command().filename(), file);
                }
                // SD_CARD entries reference a file already resident on the printer's SD card at this path -
                // nothing to upload, just send the print command directly.
                historyService.registerExpectedWeight(printerName, entry.command().filename(), entry.grams());
                detail.printer().commandPrintProjectFile(entry.command());
                removeFirst(printerName, entry);
                Log.infof("PrintQueueService: %s: started %s (plate %d)", printerName, entry.command().filename(), entry.command().plateId());
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
