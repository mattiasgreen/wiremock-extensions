package com.github.mattiasgreen.wiremock.journal.engine;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import com.github.luben.zstd.ZstdDictTrainer;
import java.util.Collection;

/**
 * High-performance Zstandard codec supporting baseline and dictionary-backed operations.
 */
public class ZstdCodec {

    private static final int DEFAULT_LEVEL = 3;
    private static final byte[] EMPTY_BYTES = new byte[0];

    public byte[] compressBaseline(byte[] src) {
        if (src == null || src.length == 0) {
            return EMPTY_BYTES;
        }
        return Zstd.compress(src, DEFAULT_LEVEL);
    }

    public byte[] decompressBaseline(byte[] compressed, int originalSize) {
        if (compressed == null || compressed.length == 0 || originalSize <= 0) {
            return EMPTY_BYTES;
        }
        return Zstd.decompress(compressed, originalSize);
    }

    public byte[] compressWithDict(byte[] src, ZstdDictCompress dictCompress) {
        if (src == null || src.length == 0) {
            return EMPTY_BYTES;
        }
        if (dictCompress == null) {
            return compressBaseline(src);
        }
        return Zstd.compress(src, dictCompress);
    }

    public byte[] decompressWithDict(byte[] compressed, ZstdDictDecompress dictDecompress, int originalSize) {
        if (compressed == null || compressed.length == 0 || originalSize <= 0) {
            return EMPTY_BYTES;
        }
        if (dictDecompress == null) {
            return decompressBaseline(compressed, originalSize);
        }
        return Zstd.decompress(compressed, dictDecompress, originalSize);
    }

    /**
     * Trains a compact Zstandard dictionary from representative payload samples.
     */
    public byte[] trainDictionary(Collection<byte[]> samples, int targetDictSizeBytes) {
        if (samples == null || samples.isEmpty()) {
            return EMPTY_BYTES;
        }

        int totalSampleSize = 0;
        for (byte[] sample : samples) {
            if (sample != null) {
                totalSampleSize += sample.length;
            }
        }

        if (totalSampleSize == 0) {
            return EMPTY_BYTES;
        }

        // Ensure dict size does not exceed sample size
        int effectiveDictSize = Math.min(targetDictSizeBytes, Math.max(256, totalSampleSize / 2));

        ZstdDictTrainer trainer = new ZstdDictTrainer(totalSampleSize, effectiveDictSize);
        for (byte[] sample : samples) {
            if (sample != null && sample.length > 0) {
                trainer.addSample(sample);
            }
        }

        try {
            return trainer.trainSamples();
        } catch (Exception e) {
            System.err.println("Dictionary training failed: " + e.getMessage());
            return EMPTY_BYTES;
        }
    }
}
