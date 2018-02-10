/*
* Copyright (C) 2016-2018 ActionTech.
* based on code by MyCATCopyrightHolder Copyright (c) 2013, OpenCloudDB/MyCAT.
* License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
*/
package com.actiontech.dble.net.handler;

import com.actiontech.dble.DbleServer;
import com.actiontech.dble.backend.mysql.MySQLMessage;
import com.actiontech.dble.config.ErrorCode;
import com.actiontech.dble.net.FrontendConnection;
import com.actiontech.dble.net.NIOHandler;
import com.actiontech.dble.net.mysql.MySQLPacket;
import com.actiontech.dble.statistic.CommandCount;
import com.actiontech.dble.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FrontendCommandHandler
 *
 * @author mycat
 */
public class FrontendCommandHandler implements NIOHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(FrontendCommandHandler.class);
    private final ConcurrentLinkedQueue<byte[]> dataQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean handleStatus;
    protected final FrontendConnection source;
    protected final CommandCount commands;

    public FrontendCommandHandler(FrontendConnection source) {
        this.source = source;
        this.commands = source.getProcessor().getCommands();
        this.handleStatus = new AtomicBoolean(false);
    }

    @Override
    public boolean handle(byte[] data) {
        if (source.getLoadDataInfileHandler() != null && source.getLoadDataInfileHandler().isStartLoadData()) {
            MySQLMessage mm = new MySQLMessage(data);
            int packetLength = mm.readUB3();
            if (packetLength + 4 == data.length) {
                source.loadDataInfileData(data);
            }
            return true;
        }
        if (dataQueue.offer(data)) {
            handleQueue();
        } else {
            throw new RuntimeException("add data to queue error.");
        }
        return true;
    }

    protected void handleData(byte[] data) {
        source.startProcess();
        switch (data[4]) {
            case MySQLPacket.COM_INIT_DB:
                commands.doInitDB();
                source.initDB(data);
                break;
            case MySQLPacket.COM_QUERY:
                commands.doQuery();
                source.query(data);
                break;
            case MySQLPacket.COM_PING:
                commands.doPing();
                source.ping();
                break;
            case MySQLPacket.COM_QUIT:
                commands.doQuit();
                source.close("quit cmd");
                break;
            case MySQLPacket.COM_PROCESS_KILL:
                commands.doKill();
                source.kill(data);
                break;
            case MySQLPacket.COM_STMT_PREPARE:
                commands.doStmtPrepare();
                source.stmtPrepare(data);
                break;
            case MySQLPacket.COM_STMT_SEND_LONG_DATA:
                commands.doStmtSendLongData();
                source.stmtSendLongData(data);
                break;
            case MySQLPacket.COM_STMT_RESET:
                commands.doStmtReset();
                source.stmtReset(data);
                break;
            case MySQLPacket.COM_STMT_EXECUTE:
                commands.doStmtExecute();
                source.stmtExecute(data);
                break;
            case MySQLPacket.COM_STMT_CLOSE:
                commands.doStmtClose();
                source.stmtClose(data);
                break;
            case MySQLPacket.COM_HEARTBEAT:
                commands.doHeartbeat();
                source.heartbeat(data);
                break;
            default:
                commands.doOther();
                source.writeErrMessage(ErrorCode.ER_UNKNOWN_COM_ERROR, "Unknown command");
        }
    }

    private void handleQueue() {
        if (this.handleStatus.compareAndSet(false, true)) {
            DbleServer.getInstance().getBusinessExecutor().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] data;
                        while ((data = dataQueue.poll()) != null) {
                            handleData(data);
                        }
                        for (int i = 0; i < DbleServer.getInstance().getConfig().getSystem().getIdleCount(); i++) {
                            while ((data = dataQueue.poll()) != null) {
                                handleData(data);
                            }
                        }

                    } catch (Exception e) {
                        String msg = e.getMessage();
                        if (StringUtil.isEmpty(msg)) {
                            LOGGER.info("Maybe occur a bug, please check it.", e);
                            msg = e.toString();
                        } else {
                            LOGGER.info("There is an error you may need know.", e);
                        }
                        source.writeErrMessage(ErrorCode.ER_UNKNOWN_ERROR, msg);
                        dataQueue.clear();
                    } finally {
                        handleStatus.set(false);
                        if (dataQueue.size() > 0) {
                            handleQueue();
                        }
                    }
                }
            });
        }
    }
}
