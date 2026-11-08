package com.github.mattiasgreen.wiremock.journal;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import com.github.luben.zstd.ZstdDictTrainer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ZstdSmokeTest {

    @Test
    void testBaselineCompressionAndDecompression() {
        String payload =
                "{\"caseId\":\"CASE-12345\",\"status\":\"OPEN\",\"message\":\"Case created successfully\",\"details\":\"A longer description providing adequate entropy and schema repetition across words and phrases.\",\"timestamp\":\"2026-11-04T03:00:00Z\"}";
        byte[] rawBytes = payload.getBytes(StandardCharsets.UTF_8);

        byte[] compressed = Zstd.compress(rawBytes, 3);
        assertThat(compressed.length).isLessThan(rawBytes.length);

        byte[] decompressed = Zstd.decompress(compressed, rawBytes.length);
        assertThat(new String(decompressed, StandardCharsets.UTF_8)).isEqualTo(payload);
    }

    @Test
    void testDictionaryTrainingAndExtremeCompression() {
        // Prepare 50 structurally repetitive samples with different IDs and timestamps
        List<byte[]> samples = new ArrayList<>();
        int sampleSizeTotal = 0;
        for (int i = 0; i < 50; i++) {
            String sample = String.format(
                    "{\"orderId\":\"ORD-%05d\",\"account\":\"ACC-998877\",\"status\":\"PROCESSED\",\"items\":[{\"sku\":\"ITEM-ABC\",\"qty\":2,\"price\":19.99},{\"sku\":\"ITEM-XYZ\",\"qty\":1,\"price\":5.50}],\"metadata\":{\"source\":\"mobile-app\",\"clientVersion\":\"3.4.1\",\"tenant\":\"US-EAST\"},\"timestamp\":\"2026-11-04T03:%02d:%02dZ\"}",
                    i, i % 60, (i * 7) % 60);
            byte[] bytes = sample.getBytes(StandardCharsets.UTF_8);
            samples.add(bytes);
            sampleSizeTotal += bytes.length;
        }

        // Train a 16 KB dictionary
        ZstdDictTrainer trainer = new ZstdDictTrainer(sampleSizeTotal, 16 * 1024);
        for (byte[] sample : samples) {
            trainer.addSample(sample);
        }
        byte[] dict = trainer.trainSamples();
        assertThat(dict).isNotEmpty();

        ZstdDictCompress dictCompress = new ZstdDictCompress(dict, 3);
        ZstdDictDecompress dictDecompress = new ZstdDictDecompress(dict);

        // Test compression of a 51st sample
        String testPayload =
                "{\"orderId\":\"ORD-00999\",\"account\":\"ACC-998877\",\"status\":\"PROCESSED\",\"items\":[{\"sku\":\"ITEM-ABC\",\"qty\":2,\"price\":19.99},{\"sku\":\"ITEM-XYZ\",\"qty\":1,\"price\":5.50}],\"metadata\":{\"source\":\"mobile-app\",\"clientVersion\":\"3.4.1\",\"tenant\":\"US-EAST\"},\"timestamp\":\"2026-11-04T03:59:59Z\"}";
        byte[] rawBytes = testPayload.getBytes(StandardCharsets.UTF_8);

        byte[] baselineCompressed = Zstd.compress(rawBytes, 3);
        byte[] dictCompressed = Zstd.compress(rawBytes, dictCompress);

        System.out.println("Raw bytes: " + rawBytes.length);
        System.out.println("Baseline compressed: " + baselineCompressed.length + " (Ratio: "
                + ((double) rawBytes.length / baselineCompressed.length) + ")");
        System.out.println("Dict compressed: " + dictCompressed.length + " (Ratio: "
                + ((double) rawBytes.length / dictCompressed.length) + ")");

        // Dict compression should be significantly smaller than baseline
        assertThat(dictCompressed.length).isLessThan(baselineCompressed.length);

        byte[] decompressed = Zstd.decompress(dictCompressed, dictDecompress, rawBytes.length);
        assertThat(new String(decompressed, StandardCharsets.UTF_8)).isEqualTo(testPayload);
    }
}
