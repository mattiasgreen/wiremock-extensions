package com.github.mattiasgreen.wiremock.casual;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.transaction.xa.Xid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.laz.casual.api.xa.XAReturnCode;
import se.laz.casual.api.xa.XID;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceRollbackReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceRollbackRequestMessage;

/**
 * Deep module that coordinates two-phase commit (2PC) transactions across upstream Casual domains
 * and local synthetic WireMock mock stubs.
 */
public class CasualTransactionCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(CasualTransactionCoordinator.class);

    private final Map<Xid, Set<CasualClient>> enlistedXidClients = new ConcurrentHashMap<>();
    private final Set<Xid> localMatchedXids = ConcurrentHashMap.newKeySet();
    private final Map<Xid, XAReturnCode> prepareVoteOverrides = new ConcurrentHashMap<>();
    private final Map<Xid, XAReturnCode> commitVoteOverrides = new ConcurrentHashMap<>();
    private final Map<Xid, XAReturnCode> rollbackVoteOverrides = new ConcurrentHashMap<>();

    public boolean isTransactional(Xid xid) {
        return xid != null && !XID.NULL_XID.equals(xid);
    }

    public void enlistUpstreamClient(Xid xid, CasualClient client) {
        if (isTransactional(xid) && client != null) {
            enlistedXidClients
                    .computeIfAbsent(xid, k -> ConcurrentHashMap.newKeySet())
                    .add(client);
        }
    }

    public void recordLocalCall(
            Xid xid,
            Optional<XAReturnCode> prepareVote,
            Optional<XAReturnCode> commitVote,
            Optional<XAReturnCode> rollbackVote) {
        if (!isTransactional(xid)) {
            return;
        }
        localMatchedXids.add(xid);
        prepareVote.ifPresent(v -> prepareVoteOverrides.put(xid, v));
        commitVote.ifPresent(v -> commitVoteOverrides.put(xid, v));
        rollbackVote.ifPresent(v -> rollbackVoteOverrides.put(xid, v));
    }

    public CasualTransactionResourcePrepareReplyMessage prepare(CasualTransactionResourcePrepareRequestMessage req) {
        Xid xid = req.getXid();
        int resourceId = req.getResourceId();
        UUID execution = req.getExecution();

        XAReturnCode finalCode = XAReturnCode.XA_OK;
        if (prepareVoteOverrides.containsKey(xid)) {
            finalCode = prepareVoteOverrides.get(xid);
            LOG.info("[CASUAL-2PC-OVERRIDE] Applying synthetic XA prepare override [{}] for Xid [{}]", finalCode, xid);
        }

        Set<CasualClient> clients = enlistedXidClients.get(xid);
        if (clients != null && !clients.isEmpty()) {
            boolean allReadOnly = true;
            for (CasualClient client : clients) {
                try {
                    CasualTransactionResourcePrepareReplyMessage reply =
                            client.prepare(xid, resourceId, req.getFlags());
                    XAReturnCode code = reply.getTransactionReturnCode();
                    if (code != XAReturnCode.XA_RDONLY) {
                        allReadOnly = false;
                    }
                    if (code != XAReturnCode.XA_OK && code != XAReturnCode.XA_RDONLY) {
                        LOG.warn("[CASUAL-2PC-PREPARE] Upstream client voted abort/error [{}] on Xid [{}]", code, xid);
                        finalCode = code;
                        break;
                    }
                } catch (Exception e) {
                    LOG.error(
                            "[CASUAL-2PC-PREPARE-FAIL] Failed prepare against upstream on Xid [{}]: {}",
                            xid,
                            e.getMessage());
                    finalCode = XAReturnCode.XA_RBROLLBACK;
                    break;
                }
            }
            if (finalCode == XAReturnCode.XA_OK && allReadOnly && !localMatchedXids.contains(xid)) {
                finalCode = XAReturnCode.XA_RDONLY;
            }
        }

        if (finalCode != XAReturnCode.XA_OK && finalCode != XAReturnCode.XA_RDONLY) {
            cleanupTransactionState(xid);
        }

        return CasualTransactionResourcePrepareReplyMessage.of(execution, xid, resourceId, finalCode);
    }

    public CasualTransactionResourceCommitReplyMessage commit(CasualTransactionResourceCommitRequestMessage req) {
        Xid xid = req.getXid();
        int resourceId = req.getResourceId();
        UUID execution = req.getExecution();

        XAReturnCode finalCode = XAReturnCode.XA_OK;
        if (commitVoteOverrides.containsKey(xid)) {
            finalCode = commitVoteOverrides.get(xid);
            LOG.info("[CASUAL-2PC-OVERRIDE] Applying synthetic XA commit override [{}] for Xid [{}]", finalCode, xid);
        }

        Set<CasualClient> clients = enlistedXidClients.get(xid);
        if (clients != null && !clients.isEmpty()) {
            for (CasualClient client : clients) {
                try {
                    CasualTransactionResourceCommitReplyMessage reply = client.commit(xid, resourceId, req.getFlags());
                    XAReturnCode code = reply.getTransactionReturnCode();
                    if (code != XAReturnCode.XA_OK) {
                        LOG.warn("[CASUAL-2PC-COMMIT] Upstream client returned non-OK [{}] on Xid [{}]", code, xid);
                        finalCode = code;
                    }
                } catch (Exception e) {
                    LOG.error(
                            "[CASUAL-2PC-COMMIT-FAIL] Failed commit against upstream on Xid [{}]: {}",
                            xid,
                            e.getMessage());
                    finalCode = XAReturnCode.XAER_RMFAIL;
                }
            }
        }

        cleanupTransactionState(xid);
        return CasualTransactionResourceCommitReplyMessage.of(execution, xid, resourceId, finalCode);
    }

    public CasualTransactionResourceRollbackReplyMessage rollback(CasualTransactionResourceRollbackRequestMessage req) {
        Xid xid = req.getXid();
        int resourceId = req.getResourceId();
        UUID execution = req.getExecution();

        XAReturnCode finalCode = XAReturnCode.XA_OK;
        if (rollbackVoteOverrides.containsKey(xid)) {
            finalCode = rollbackVoteOverrides.get(xid);
            LOG.info("[CASUAL-2PC-OVERRIDE] Applying synthetic XA rollback override [{}] for Xid [{}]", finalCode, xid);
        }

        Set<CasualClient> clients = enlistedXidClients.get(xid);
        if (clients != null && !clients.isEmpty()) {
            for (CasualClient client : clients) {
                try {
                    CasualTransactionResourceRollbackReplyMessage reply =
                            client.rollback(xid, resourceId, req.getFlags());
                    XAReturnCode code = reply.getTransactionReturnCode();
                    if (code != XAReturnCode.XA_OK) {
                        LOG.warn("[CASUAL-2PC-ROLLBACK] Upstream client returned non-OK [{}] on Xid [{}]", code, xid);
                        finalCode = code;
                    }
                } catch (Exception e) {
                    LOG.error(
                            "[CASUAL-2PC-ROLLBACK-FAIL] Failed rollback against upstream on Xid [{}]: {}",
                            xid,
                            e.getMessage());
                    finalCode = XAReturnCode.XAER_RMFAIL;
                }
            }
        }

        cleanupTransactionState(xid);
        return CasualTransactionResourceRollbackReplyMessage.of(execution, xid, resourceId, finalCode);
    }

    public void cleanupTransactionState(Xid xid) {
        if (xid != null) {
            enlistedXidClients.remove(xid);
            localMatchedXids.remove(xid);
            prepareVoteOverrides.remove(xid);
            commitVoteOverrides.remove(xid);
            rollbackVoteOverrides.remove(xid);
        }
    }

    public boolean hasPendingTransactions() {
        return !enlistedXidClients.isEmpty()
                || !localMatchedXids.isEmpty()
                || !prepareVoteOverrides.isEmpty()
                || !commitVoteOverrides.isEmpty()
                || !rollbackVoteOverrides.isEmpty();
    }
}
