package se.laz.casual.server;

import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceRollbackReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceRollbackRequestMessage;

/**
 * Handler interface for processing incoming Casual 2PC/XA transaction requests
 * (prepare, commit, rollback).
 * <p>
 * Intended for upstream contribution to {@code se.laz.casual:casual-server}.
 */
public interface CasualTransactionHandler {

    CasualTransactionResourcePrepareReplyMessage prepare(CasualTransactionResourcePrepareRequestMessage request);

    CasualTransactionResourceCommitReplyMessage commit(CasualTransactionResourceCommitRequestMessage request);

    CasualTransactionResourceRollbackReplyMessage rollback(CasualTransactionResourceRollbackRequestMessage request);
}
