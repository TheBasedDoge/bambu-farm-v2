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
 * Maps an Etsy listing (optionally down to a specific variation combination, e.g. color/size) to a gcode project
 * file + plate in the batch print library. Persisted as JSON, same pattern as {@link PrintQueueService}.
 */
@ApplicationScoped
public class EtsyMappingService {

    public record MappingKey(long listingId, String variationSignature) {

        /**
         * Builds the signature used to key a mapping: variations sorted by name, joined as "name=value;name=value".
         * Two transactions of the same listing with the same variation choices resolve to the same signature so a
         * mapping is reused automatically.
         */
        public static String signatureOf(final List<EtsyApiClient.Variation> variations) {
            return variations.stream()
                    .sorted((a, b) -> a.propertyName().compareToIgnoreCase(b.propertyName()))
                    .map(v -> v.propertyName() + "=" + v.value())
                    .reduce((a, b) -> a + ";" + b)
                    .orElse("");
        }

        public String storageKey() {
            return listingId + "|" + variationSignature;
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
        return Path.of(config.etsy().mappingFile());
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
            Log.infof("EtsyMappingService: loaded %d listing mapping(s) from %s", loaded.size(), path);
        } catch (IOException ex) {
            Log.errorf(ex, "EtsyMappingService: cannot load %s: %s", path, ex.getMessage());
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
            Log.errorf(ex, "EtsyMappingService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    @Shutdown
    void onShutdown() {
        save();
    }

    /** Looks up a mapping for the exact listing+variation combination, falling back to a listing-wide (no-variation) mapping. */
    public synchronized Optional<MappingEntry> find(final long listingId, final List<EtsyApiClient.Variation> variations) {
        final String signature = MappingKey.signatureOf(variations);
        final Optional<MappingEntry> exact = Optional.ofNullable(data.get(new MappingKey(listingId, signature).storageKey()));
        if (exact.isPresent()) {
            return exact;
        }
        return Optional.ofNullable(data.get(new MappingKey(listingId, "").storageKey()));
    }

    /**
     * The storage key a lookup for this listing+variation would resolve to (the exact variation key if one is
     * stored, else the listing-wide base key), or empty when neither is mapped. Used to key on-hand stock to the
     * same granularity as the mapping.
     */
    public synchronized Optional<String> findKey(final long listingId, final List<EtsyApiClient.Variation> variations) {
        final String signature = MappingKey.signatureOf(variations);
        final String exact = new MappingKey(listingId, signature).storageKey();
        if (data.containsKey(exact)) {
            return Optional.of(exact);
        }
        final String base = new MappingKey(listingId, "").storageKey();
        return data.containsKey(base) ? Optional.of(base) : Optional.empty();
    }

    public synchronized void set(final long listingId, final List<EtsyApiClient.Variation> variations, final MappingEntry entry) {
        final String signature = MappingKey.signatureOf(variations);
        data.put(new MappingKey(listingId, signature).storageKey(), entry);
        dirty = true;
        save();
    }

    public synchronized void remove(final long listingId, final List<EtsyApiClient.Variation> variations) {
        final String signature = MappingKey.signatureOf(variations);
        data.remove(new MappingKey(listingId, signature).storageKey());
        dirty = true;
        save();
    }

    /** All stored mappings by storage key ("listingId|variationSignature"), for the Mappings tab. */
    public synchronized Map<String, MappingEntry> entries() {
        return Map.copyOf(data);
    }

    /** Replaces a mapping by its raw storage key (used by the Mappings tab's editor). */
    public synchronized void putByKey(final String storageKey, final MappingEntry entry) {
        data.put(storageKey, entry);
        dirty = true;
        save();
    }

    /** Deletes a mapping by its raw storage key (used by the Mappings tab). */
    public synchronized void removeByKey(final String storageKey) {
        if (data.remove(storageKey) != null) {
            dirty = true;
            save();
        }
    }

}
