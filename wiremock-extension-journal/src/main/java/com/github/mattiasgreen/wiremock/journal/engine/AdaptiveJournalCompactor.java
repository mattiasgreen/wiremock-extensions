package com.github.mattiasgreen.wiremock.journal.engine;

import com.github.mattiasgreen.wiremock.journal.model.ClusterSignature;
import com.github.mattiasgreen.wiremock.journal.model.CompressedServeEvent;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.Objects;

/**
 * Deep module encapsulating the compression lifecycle, dictionary training coordination,
 * search token extraction, and transient payload decompression.
 * Produces pure, immutable {@link CompressedServeEvent} value records containing zero engine references.
 */
public class AdaptiveJournalCompactor {

    private final ZstdCodec codec;
    private final DictionaryPool dictionaryPool;
    private final JournalSearchIndex searchIndex;
    private final JournalConfig config;

    public AdaptiveJournalCompactor() {
        this(JournalConfig.loadDefault());
    }

    public AdaptiveJournalCompactor(JournalConfig config) {
        this(new ZstdCodec(), new DictionaryPool(config), new JournalSearchIndex(), config);
    }

    public AdaptiveJournalCompactor(
            ZstdCodec codec, DictionaryPool dictionaryPool, JournalSearchIndex searchIndex, JournalConfig config) {
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.dictionaryPool = Objects.requireNonNull(dictionaryPool, "dictionaryPool must not be null");
        this.searchIndex = Objects.requireNonNull(searchIndex, "searchIndex must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public ZstdCodec getCodec() {
        return codec;
    }

    public DictionaryPool getDictionaryPool() {
        return dictionaryPool;
    }

    public JournalSearchIndex getSearchIndex() {
        return searchIndex;
    }

    public JournalConfig getConfig() {
        return config;
    }

    /**
     * Compacts an incoming ServeEvent into a pure CompressedServeEvent value object.
     * Indexes search tokens while raw payloads are in hand and offers response samples for training.
     */
    public CompressedServeEvent compact(ServeEvent original) {
        if (original instanceof CompressedServeEvent c) {
            return c;
        }

        ClusterSignature signature = ClusterSignature.of(original);

        byte[] rawReq = original.getRequest() != null ? original.getRequest().getBody() : new byte[0];
        int rawReqLen = rawReq != null ? rawReq.length : 0;

        byte[] rawResp = original.getResponse() != null ? original.getResponse().getBody() : new byte[0];
        int rawRespLen = rawResp != null ? rawResp.length : 0;

        byte[] compressedReq;
        byte[] compressedResp;
        boolean usedDict;

        if (!config.compressionEnabled()) {
            // Uncompressed mode: payloads are retained raw
            compressedReq = rawReq != null ? rawReq : new byte[0];
            compressedResp = rawResp != null ? rawResp : new byte[0];
            usedDict = false;
        } else {
            DictionaryPool.CompiledDictionary dict = dictionaryPool.getDictionary(signature);
            if (dict != null) {
                usedDict = true;
                compressedReq = codec.compressWithDict(rawReq, dict.compressor());
                compressedResp = codec.compressWithDict(rawResp, dict.compressor());
            } else {
                // Collect sample for asynchronous dictionary training
                if (rawResp != null && rawResp.length > 0) {
                    dictionaryPool.recordSample(signature, rawResp);
                }

                // Check if sample collection immediately graduated a dictionary
                dict = dictionaryPool.getDictionary(signature);
                if (dict != null) {
                    usedDict = true;
                    compressedReq = codec.compressWithDict(rawReq, dict.compressor());
                    compressedResp = codec.compressWithDict(rawResp, dict.compressor());
                } else {
                    usedDict = false;
                    compressedReq = codec.compressBaseline(rawReq);
                    compressedResp = codec.compressBaseline(rawResp);
                }
            }
        }

        // Index search tokens while raw bytes are in memory
        searchIndex.index(original.getId(), original);

        return new CompressedServeEvent(
                original,
                signature,
                compressedReq,
                compressedResp,
                rawReqLen,
                rawRespLen,
                usedDict,
                this::decompressPayload);
    }

    /**
     * Upgrades an existing baseline event to a specialized dictionary compression format.
     */
    public CompressedServeEvent recompact(CompressedServeEvent oldEvent, ClusterSignature targetSig) {
        if (!config.compressionEnabled() || oldEvent == null || targetSig == null) {
            return oldEvent;
        }

        DictionaryPool.CompiledDictionary dict = dictionaryPool.getDictionary(targetSig);
        if (dict == null) {
            return oldEvent;
        }

        byte[] rawReq = oldEvent.getRequest() != null ? oldEvent.getRequest().getBody() : new byte[0];
        byte[] rawResp = oldEvent.getResponse() != null ? oldEvent.getResponse().getBody() : new byte[0];

        byte[] compressedReq = codec.compressWithDict(rawReq, dict.compressor());
        byte[] compressedResp = codec.compressWithDict(rawResp, dict.compressor());

        return new CompressedServeEvent(
                oldEvent,
                targetSig,
                compressedReq,
                compressedResp,
                oldEvent.getRawRequestSize(),
                oldEvent.getRawResponseSize(),
                true,
                this::decompressPayload);
    }

    /**
     * Transiently decompresses a payload on demand.
     */
    public byte[] decompressPayload(
            byte[] compressedBytes, int originalSize, ClusterSignature signature, boolean usedDictionary) {
        if (!config.compressionEnabled()) {
            return compressedBytes != null ? compressedBytes : new byte[0];
        }

        if (compressedBytes == null || compressedBytes.length == 0 || originalSize <= 0) {
            return new byte[0];
        }

        if (usedDictionary && signature != null) {
            DictionaryPool.CompiledDictionary dict = dictionaryPool.getDictionary(signature);
            if (dict != null) {
                return codec.decompressWithDict(compressedBytes, dict.decompressor(), originalSize);
            }
        }

        return codec.decompressBaseline(compressedBytes, originalSize);
    }

    public void clear() {
        dictionaryPool.clear();
        searchIndex.clear();
    }
}
