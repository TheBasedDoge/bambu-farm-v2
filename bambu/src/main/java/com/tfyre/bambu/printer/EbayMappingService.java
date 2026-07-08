package com.tfyre.bambu.printer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Shutdown;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps an eBay listing (identified by SKU, falling back to the legacy item id when no SKU is set) - optionally down
 * to a specific variation combination - to a gcode project file + plate in the batch print library. Mirrors
 * {@link EtsyMappingService}.
 */
@ApplicationScoped
public class EbayMappingService {

    public record MappingKey(String listingKey, String variationSignature) {

        public static String signatureOf(final List<EbayApiClient.Variation> variations) {
            return variations.stream()
                    .sorted((a, b) -> a.propertyName().compareToIgnoreCase(b.propertyName()))
                    .map(v -> v.propertyName() + "=" + v.value())
                    .reduce((a, b) -> a + ";" + b)
                    .orElse("");
        }

        public String storageKey() {
            return listingKey + "|" + variationSignature;
        }
    }

    /** A listing's print recipe: one or more parts, each possibly needing several copies per unit ordered. */
    public record MappingEntry(List<MappingPart> parts) {
    }

    @Inject
    BambuConfig config;
    @Inject
    ObjectMapper mapper;

    private final Map<String, MappingEntry> data = new HashMap<>();
    private boolean dirty;

    private Path getPath() {
        return Path.of(config.ebay().mappingFile());
    }

    @PostConstruct
    synchronized void load() {
        final Path path = getPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            final Map<String, MappingEntry> loaded = mapper.readValue(path.toFile(), new TypeReference<Map<String, MappingEntry>>() {
            });
            data.putAll(loaded);
            Log.infof("EbayMappingService: loaded %d listing mapping(s) from %s", loaded.size(), path);
        } catch (IOException ex) {
            Log.errorf(ex, "EbayMappingService: cannot load %s: %s", path, ex.getMessage());
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
            Log.errorf(ex, "EbayMappingService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    @Shutdown
    void onShutdown() {
        save();
    }

    public synchronized Optional<MappingEntry> find(final String listingKey, final List<EbayApiClient.Variation> variations) {
        final String signature = MappingKey.signatureOf(variations);
        final Optional<MappingEntry> exact = Optional.ofNullable(data.get(new MappingKey(listingKey, signature).storageKey()));
        if (exact.isPresent()) {
            return exact;
        }
        return Optional.ofNullable(data.get(new MappingKey(listingKey, "").storageKey()));
    }

    public synchronized void set(final String listingKey, final List<EbayApiClient.Variation> variations, final MappingEntry entry) {
        final String signature = MappingKey.signatureOf(variations);
        data.put(new MappingKey(listingKey, signature).storageKey(), entry);
        dirty = true;
        save();
    }

    public synchronized void remove(final String listingKey, final List<EbayApiClient.Variation> variations) {
        final String signature = MappingKey.signatureOf(variations);
        data.remove(new MappingKey(listingKey, signature).storageKey());
        dirty = true;
        save();
    }

}
