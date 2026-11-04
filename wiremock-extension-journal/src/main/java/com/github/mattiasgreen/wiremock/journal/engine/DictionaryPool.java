package com.github.mattiasgreen.wiremock.journal.engine;

import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import com.github.mattiasgreen.wiremock.journal.model.ClusterSignature;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages an LRU pool of cluster-specific Zstandard dictionaries and asynchronous training.
 */
public class DictionaryPool {

    public static final int DEFAULT_MAX_DICTIONARIES = 64;
    public static final int DEFAULT_TRAINING_SAMPLE_THRESHOLD = 20;
    public static final int DEFAULT_DICT_SIZE_BYTES = 32 * 1024;

    public record CompiledDictionary(ZstdDictCompress compressor, ZstdDictDecompress decompressor, byte[] rawDict) {}

    private final int maxDictionaries;
    private final int sampleThreshold;
    private final int targetDictSizeBytes;
    private final ZstdCodec codec;

    // LRU cache for compiled dictionaries
    private final Map<ClusterSignature, CompiledDictionary> dictionaryCache;

    // Sample buffers for training
    private final Map<ClusterSignature, List<byte[]>> sampleBuffers = new ConcurrentHashMap<>();
    private final Set<ClusterSignature> trainingInProgress = ConcurrentHashMap.newKeySet();
    private final java.util.List<java.util.function.Consumer<ClusterSignature>> trainingListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public DictionaryPool() {
        this(JournalConfig.loadDefault());
    }

    public DictionaryPool(JournalConfig config) {
        this(config.maxDictionaries(), config.trainingSampleThreshold(), config.targetDictSizeBytes(), new ZstdCodec());
    }

    public DictionaryPool(int maxDictionaries, int sampleThreshold, int targetDictSizeBytes, ZstdCodec codec) {
        this.maxDictionaries = maxDictionaries;
        this.sampleThreshold = sampleThreshold;
        this.targetDictSizeBytes = targetDictSizeBytes;
        this.codec = Objects.requireNonNull(codec, "codec must not be null");

        // Thread-safe bounded LRU
        this.dictionaryCache = Collections.synchronizedMap(new BoundedLruMap(maxDictionaries));
    }

    public int getMaxDictionaries() {
        return maxDictionaries;
    }

    private static final class BoundedLruMap extends LinkedHashMap<ClusterSignature, CompiledDictionary> {
        private static final long serialVersionUID = 1L;
        private final int maxCapacity;

        BoundedLruMap(int maxCapacity) {
            super(16, 0.75f, true);
            this.maxCapacity = maxCapacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<ClusterSignature, CompiledDictionary> eldest) {
            return size() > maxCapacity;
        }
    }

    public void onDictionaryTrained(java.util.function.Consumer<ClusterSignature> listener) {
        if (listener != null) {
            trainingListeners.add(listener);
        }
    }

    public CompiledDictionary getDictionary(ClusterSignature signature) {
        return dictionaryCache.get(signature);
    }

    public int getActiveDictionaryCount() {
        return dictionaryCache.size();
    }

    public boolean hasDictionary(ClusterSignature signature) {
        return dictionaryCache.containsKey(signature);
    }

    /**
     * Offers a payload sample to train a specialized dictionary for this cluster.
     * When sampleThreshold is reached, training triggers asynchronously on a virtual thread.
     */
    public void recordSample(ClusterSignature signature, byte[] payload) {
        if (payload == null || payload.length == 0 || hasDictionary(signature)) {
            return;
        }

        if (trainingInProgress.contains(signature)) {
            return;
        }

        List<byte[]> samples =
                sampleBuffers.computeIfAbsent(signature, k -> Collections.synchronizedList(new ArrayList<>()));
        samples.add(payload);

        if (samples.size() >= sampleThreshold) {
            triggerAsyncTraining(signature);
        }
    }

    public void triggerAsyncTraining(ClusterSignature signature) {
        if (!trainingInProgress.add(signature)) {
            return;
        }

        List<byte[]> samples = sampleBuffers.remove(signature);
        if (samples == null || samples.isEmpty()) {
            trainingInProgress.remove(signature);
            return;
        }

        Thread.ofVirtual().name("zstd-trainer-" + signature.key()).start(() -> {
            try {
                byte[] dictBytes = codec.trainDictionary(samples, targetDictSizeBytes);
                if (dictBytes != null && dictBytes.length > 0) {
                    ZstdDictCompress compress = new ZstdDictCompress(dictBytes, 3);
                    ZstdDictDecompress decompress = new ZstdDictDecompress(dictBytes);
                    dictionaryCache.put(signature, new CompiledDictionary(compress, decompress, dictBytes));
                    for (java.util.function.Consumer<ClusterSignature> listener : trainingListeners) {
                        try {
                            listener.accept(signature);
                        } catch (RuntimeException e) {
                            System.getLogger(DictionaryPool.class.getName())
                                    .log(
                                            System.Logger.Level.DEBUG,
                                            "Dictionary training listener threw exception: {0}",
                                            e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Async training failed: " + e.getMessage());
            } finally {
                trainingInProgress.remove(signature);
            }
        });
    }

    public void clear() {
        sampleBuffers.clear();
        trainingInProgress.clear();
        synchronized (dictionaryCache) {
            for (CompiledDictionary dict : dictionaryCache.values()) {
                dict.compressor().close();
                dict.decompressor().close();
            }
            dictionaryCache.clear();
        }
    }
}
