package com.devebot.opflow;

import com.devebot.opflow.OpflowLogTracer.Customizer;
import com.devebot.opflow.supports.OpflowDateTime;
import com.devebot.opflow.supports.OpflowEnvTool;
import com.devebot.opflow.supports.OpflowObjectTree;
import java.util.Map;

/**
 *
 * @author acegik
 */
public class OpflowRpcParameter implements Customizer {
    private final static OpflowConstant CONST = OpflowConstant.CURRENT();

    private final static boolean IS_PING_LOGGING_OMITTED;

    static {
        IS_PING_LOGGING_OMITTED = !"false".equals(OpflowEnvTool.instance.getSystemProperty("OPFLOW_OMIT_PING_LOGS", null));
    }

    private final String routineId;
    private final String routineTimestamp;
    private String[] routineTags = null;
    private Long routineTTL = null;
    private String routineSignature = null;
    private String routineScope = null;
    private Boolean callbackTransient = false;
    private Boolean progressEnabled = null;

    public OpflowRpcParameter() {
        this.routineId = OpflowUUID.getBase64ID();
        this.routineTimestamp = OpflowDateTime.getCurrentTimeString();
    }

    public OpflowRpcParameter(Map<String, Object> headers) {
        headers = OpflowObjectTree.ensureNonNull(headers);

        this.routineId = OpflowUtil.getRoutineId(headers);
        this.routineSignature = OpflowUtil.getRoutineSignature(headers);
        this.routineTimestamp = OpflowUtil.getRoutineTimestamp(headers);
        this.routineTags = OpflowUtil.getRoutineTags(headers);
        this.routineScope = OpflowUtil.getRoutineScope(headers);
        this.progressEnabled = OpflowUtil.getProgressEnabled(headers);

        if (headers.get("timeout") instanceof Long) {
            this.routineTTL = (Long) headers.get("timeout");
        }

        this.callbackTransient = "forked".equals((String)headers.get("mode"));
    }
    
    public OpflowRpcParameter(String routineId, String routineTimestamp) {
        this.routineId = routineId;
        this.routineTimestamp = routineTimestamp;
    }

    public String getRoutineSignature() {
        return routineSignature;
    }

    public OpflowRpcParameter setRoutineSignature(String routineSignature) {
        this.routineSignature = routineSignature;
        return this;
    }
    
    public String getRoutineId() {
        return routineId;
    }

    public String getRoutineTimestamp() {
        return routineTimestamp;
    }

    public String[] getRoutineTags() {
        return routineTags;
    }

    public OpflowRpcParameter setRoutineTags(String[] requestTags) {
        this.routineTags = requestTags;
        return this;
    }

    public Long getRoutineTTL() {
        return routineTTL;
    }

    public OpflowRpcParameter setRoutineTTL(Long routineTTL) {
        this.routineTTL = routineTTL;
        return this;
    }
    
    public String getRoutineScope() {
        return routineScope;
    }
    
    public OpflowRpcParameter setRoutineScope(String routineScope) {
        this.routineScope = routineScope;
        return this;
    }
    
    public Boolean getCallbackTransient() {
        return callbackTransient;
    }

    public OpflowRpcParameter setCallbackTransient(Boolean callbackTransient) {
        this.callbackTransient = callbackTransient;
        return this;
    }

    public Boolean getProgressEnabled() {
        return progressEnabled;
    }

    public OpflowRpcParameter setProgressEnabled(Boolean progressEnabled) {
        this.progressEnabled = progressEnabled;
        return this;
    }

    @Override
    public boolean isMute() {
        return IS_PING_LOGGING_OMITTED && "internal".equals(routineScope);
    }
}
