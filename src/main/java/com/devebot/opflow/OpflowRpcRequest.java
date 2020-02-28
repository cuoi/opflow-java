package com.devebot.opflow;

import com.devebot.opflow.OpflowLogTracer.Level;
import com.devebot.opflow.supports.OpflowJsonTool;
import com.devebot.opflow.exception.OpflowJsonTransformationException;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author drupalex
 */
public class OpflowRpcRequest implements Iterator, OpflowTimeout.Timeoutable {
    private final static OpflowConstant CONST = OpflowConstant.CURRENT();
    private final static Logger LOG = LoggerFactory.getLogger(OpflowRpcRequest.class);
    private final OpflowLogTracer reqTracer;
    private final String routineId;
    private final String routineTimestamp;
    private final String routineSignature;
    private final long timeout;
    private final OpflowTimeout.Listener completeListener;
    private OpflowTimeout.Watcher timeoutWatcher;
    private long timestamp;

    public OpflowRpcRequest(final OpflowRpcParameter params, final OpflowTimeout.Listener completeListener) {
        this.routineId = params.getRoutineId();
        this.routineSignature = params.getRoutineSignature();
        this.routineTimestamp = params.getRoutineTimestamp();
        
        if (params.getRoutineTTL() == null) {
            this.timeout = 0l;
        } else {
            this.timeout = params.getRoutineTTL();
        }
        
        reqTracer = OpflowLogTracer.ROOT.branch(CONST.REQUEST_TIME, this.routineTimestamp)
                .branch(CONST.REQUEST_ID, this.routineId, params);

        this.completeListener = completeListener;

        if (params.getWatcherEnabled() && completeListener != null && this.timeout > 0) {
            timeoutWatcher = new OpflowTimeout.Watcher(this.routineId, this.timeout, new OpflowTimeout.Listener() {
                @Override
                public void handleEvent() {
                    OpflowLogTracer logWatcher = null;
                    if (reqTracer.ready(LOG, Level.DEBUG)) {
                        logWatcher = reqTracer.copy();
                    }
                    if (logWatcher != null && logWatcher.ready(LOG, Level.DEBUG)) LOG.debug(logWatcher
                            .text("Request[${requestId}][${requestTime}][x-rpc-request-watcher-timeout] timeout event has been raised")
                            .stringify());
                    list.add(OpflowMessage.ERROR);
                    if (logWatcher != null && logWatcher.ready(LOG, Level.DEBUG)) LOG.debug(logWatcher
                            .text("Request[${requestId}][${requestTime}] raise completeListener (timeout)")
                            .stringify());
                    completeListener.handleEvent();
                }
            });
            timeoutWatcher.start();
        }

        checkTimestamp();
    }
    
    public OpflowRpcRequest(final Map<String, Object> options, final OpflowTimeout.Listener completeListener) {
        this(new OpflowRpcParameter(options), completeListener);
    }
    
    public String getRoutineId() {
        return routineId;
    }

    public String getRoutineTimestamp() {
        return routineTimestamp;
    }

    public String getRoutineSignature() {
        return routineSignature;
    }

    @Override
    public long getTimeout() {
        if (this.timeout <= 0) return 0;
        return this.timeout;
    }
    
    @Override
    public long getTimestamp() {
        return this.timestamp;
    }
    
    @Override
    public void raiseTimeout() {
        this.push(OpflowMessage.ERROR);
    }
    
    private final BlockingQueue<OpflowMessage> list = new LinkedBlockingQueue<>();
    private OpflowMessage current = null;
    
    @Override
    public boolean hasNext() {
        try {
            this.current = list.take();
            if (this.current == OpflowMessage.EMPTY) return false;
            if (this.current == OpflowMessage.ERROR) return false;
            return true;
        } catch (InterruptedException ie) {
            return false;
        }
    }

    @Override
    public OpflowMessage next() {
        OpflowMessage result = this.current;
        this.current = null;
        return result;
    }
    
    public void push(OpflowMessage message) {
        list.add(message);
        if (timeoutWatcher != null) {
            timeoutWatcher.check();
        }
        checkTimestamp();
        if(isDone(message)) {
            OpflowLogTracer pushTrail = null;
            if (reqTracer.ready(LOG, Level.DEBUG)) {
                pushTrail = reqTracer.copy();
            }
            if (pushTrail != null && pushTrail.ready(LOG, Level.DEBUG)) LOG.debug(pushTrail
                    .text("Request[${requestId}][${requestTime}][x-rpc-request-finished] has completed/failed message")
                    .stringify());
            list.add(OpflowMessage.EMPTY);
            if (completeListener != null) {
                if (pushTrail != null && pushTrail.ready(LOG, Level.DEBUG)) LOG.debug(pushTrail
                        .text("Request[${requestId}][${requestTime}][x-rpc-request-callback] raises completeListener (completed)")
                        .stringify());
                completeListener.handleEvent();
            }
            if (timeoutWatcher != null) {
                timeoutWatcher.close();
            }
        }
    }
    
    public List<OpflowMessage> iterateResult() {
        List<OpflowMessage> buff = new LinkedList<>();
        while(this.hasNext()) buff.add(this.next());
        return buff;
    }
    
    public OpflowRpcResult extractResult() {
        return extractResult(true);
    }
    
    public OpflowRpcResult extractResult(final boolean includeProgress) {
        OpflowLogTracer extractTrail = reqTracer;
        if (extractTrail != null && extractTrail.ready(LOG, Level.TRACE)) LOG.trace(extractTrail
                .text("Request[${requestId}][${requestTime}][x-rpc-request-extract-result-begin] - extracting result")
                .stringify());
        String consumerTag = null;
        boolean failed = false;
        byte[] error = null;
        boolean completed = false;
        byte[] value = null;
        List<OpflowRpcResult.Step> steps = new LinkedList<>();
        while(this.hasNext()) {
            OpflowMessage msg = this.next();
            String status = getStatus(msg);
            if (extractTrail != null && extractTrail.ready(LOG, Level.TRACE)) LOG.trace(extractTrail
                    .put("status", status)
                    .text("Request[${requestId}][${requestTime}][x-rpc-request-examine-status] - examine message, status: ${status}")
                    .stringify());
            if (status == null) continue;
            switch (status) {
                case "progress":
                    if (includeProgress) {
                        try {
                            int percent = OpflowJsonTool.extractFieldAsInt(msg.getBodyAsString(), "percent");
                            steps.add(new OpflowRpcResult.Step(percent));
                        } catch (OpflowJsonTransformationException jse) {
                            steps.add(new OpflowRpcResult.Step());
                        }
                    }   break;
                case "failed":
                    consumerTag = OpflowUtil.getMessageField(msg, "consumerTag");
                    failed = true;
                    error = msg.getBody();
                    break;
                case "completed":
                    consumerTag = OpflowUtil.getMessageField(msg, "consumerTag");
                    completed = true;
                    value = msg.getBody();
                    break;
                default:
                    break;
            }
        }
        if (extractTrail != null && extractTrail.ready(LOG, Level.TRACE)) LOG.trace(extractTrail
                .text("Request[${requestId}][${requestTime}][x-rpc-request-extract-result-end] - extracting result has completed")
                .stringify());
        if (!includeProgress) steps = null;
        return new OpflowRpcResult(routineSignature, routineId, consumerTag, steps, failed, error, completed, value);
    }
    
    private static final List<String> STATUS = Arrays.asList(new String[] { "failed", "completed" });
    
    private boolean isDone(OpflowMessage message) {
        String status = getStatus(message);
        if (status == null) return false;
        return STATUS.indexOf(status) >= 0;
    }
    
    private void checkTimestamp() {
        timestamp = (new Date()).getTime();
    }
    
    private static long getRequestTimeout(Map<String, Object> opts) {
        if (opts == null) {
            return 0;
        }
        final Object opts_timeout = opts.get("timeout");
        if (opts_timeout == null) {
            return 0;
        } else if (opts_timeout instanceof Long) {
            return (Long)opts_timeout;
        } else if (opts_timeout instanceof Integer) {
            return ((Integer)opts_timeout).longValue();
        } else {
            return 0;
        }
    }
    
    public static String getStatus(OpflowMessage message) {
        return OpflowUtil.getMessageField(message, "status");
    }
}
