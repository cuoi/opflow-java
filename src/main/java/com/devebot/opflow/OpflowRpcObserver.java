package com.devebot.opflow;

import com.devebot.opflow.annotation.OpflowFieldExclude;
import com.devebot.opflow.supports.OpflowConcurrentMap;
import com.devebot.opflow.supports.OpflowDateTime;
import com.devebot.opflow.supports.OpflowRevolvingMap;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 *
 * @author acegik
 */
public class OpflowRpcObserver {
    private final static OpflowConstant CONST = OpflowConstant.CURRENT();
    private final static long KEEP_ALIVE_TIMEOUT = 20000;
    
    private long keepAliveTimeout = 2 * KEEP_ALIVE_TIMEOUT;
    private final OpflowConcurrentMap<String, OpflowRpcObserver.Manifest> manifests = new OpflowConcurrentMap<>();
    private final OpflowRevolvingMap<String, OpflowRpcLocation> amqpCongestion = new OpflowRevolvingMap<>();
    private final OpflowRevolvingMap<String, OpflowRpcLocation> httpCongestion = new OpflowRevolvingMap<>();
    
    private String latestAddress = null;
    
    private boolean congestive = false;
    private boolean congestiveAMQP = false;
    private boolean congestiveHTTP = false;
    
    public enum Protocol { AMQP, HTTP, NONE };
    
    public void check(final OpflowRpcObserver.Protocol protocol, final Map<String, Object> headers) {
        switch (protocol) {
            case AMQP:
                String componentId = OpflowUtil.getStringField(headers, CONST.AMQP_HEADER_CONSUMER_ID, false, true);
                if (componentId != null) {
                    OpflowRpcObserver.Manifest manifest = assertManifest(componentId);
                    // inform the manifest status
                    manifest.updatedTimestamp = manifest.updatedAMQPTimestamp = new Date();
                    // update the protocol version
                    String version = OpflowUtil.getStringField(headers, CONST.AMQP_HEADER_PROTOCOL_VERSION, false, true);
                    manifest.information.put("AMQP_PROTOCOL_VERSION", version != null ? version : "0");
                    // update the http address
                    String address = OpflowUtil.getStringField(headers, OpflowConstant.OPFLOW_COMMON_ADDRESS, false, true);
                    manifest.address = address;
                    // update the newest components
                    if (address != null) {
                        this.latestAddress = address;
                    }
                }
                break;
            case HTTP:
                String httpWorkerId = OpflowUtil.getStringField(headers, OpflowConstant.OPFLOW_PROTO_RES_HEADER_WORKER_ID, false, true);
                if (httpWorkerId != null) {
                    OpflowRpcObserver.Manifest manifest = assertManifest(httpWorkerId);
                    manifest.updatedTimestamp = manifest.updatedHTTPTimestamp = new Date();
                }
                break;
        }
    }
    
    public boolean isCongestive() {
        return isCongestive(Protocol.NONE);
    }
    
    public void setCongestive(boolean congestive) {
        setCongestive(Protocol.NONE, congestive);
    }
    
    public boolean isCongestive(Protocol protocol) {
        switch (protocol) {
            case AMQP:
                return congestiveAMQP;
            case HTTP:
                return congestiveHTTP;
        }
        return congestive;
    }
    
    public void setCongestive(Protocol protocol, boolean congestive) {
        switch (protocol) {
            case AMQP:
                this.congestiveAMQP = congestive;
                break;
            case HTTP:
                this.congestiveHTTP = congestive;
                break;
            default:
                this.congestive = congestive;
                break;
        }
    }
    
    public String getLatestAddress() {
        return this.latestAddress;
    }
    
    public boolean containsInfo(String componentId, String name) {
        if (componentId == null) return false;
        OpflowRpcObserver.Manifest manifest = manifests.get(componentId);
        if (manifest == null) return false;
        return (manifest.information.containsKey(name));
    }
    
    public void updateInfo(String componentId, String name, Object data) {
        if (componentId == null) return;
        if (manifests.containsKey(componentId)) {
            OpflowRpcObserver.Manifest manifest = manifests.get(componentId);
            if (manifest != null) {
                synchronized (manifest.information) {
                    manifest.information.put(name, data);
                }
            }
        }
    }
    
    public Collection<OpflowRpcObserver.Manifest> rollup() {
        Set<String> keys = manifests.keySet();
        for (String key: keys) {
            OpflowRpcObserver.Manifest manifest = manifests.get(key);
            // refresh the state of the manifest
            manifest.refresh(keepAliveTimeout);
            // filter the active manifests
            if (manifest.expired()) {
                manifests.remove(key);
            }
        }
        return manifests.values();
    }
    
    public Object summary() {
        return this.rollup();
    }
    
    public void setKeepAliveTimeout(long timeout) {
        if (timeout > 0) {
            this.keepAliveTimeout = KEEP_ALIVE_TIMEOUT + Long.min(KEEP_ALIVE_TIMEOUT, timeout);
        }
    }
    
    private Manifest assertManifest(String componentId) {
        OpflowRpcObserver.Manifest manifest = null;
        if (manifests.containsKey(componentId)) {
            manifest = manifests.get(componentId);
        } else {
            manifest = new OpflowRpcObserver.Manifest(componentId);
            manifests.put(componentId, manifest);
        }
        return manifest;
    }
    
    public static class Manifest {
        public final static String STATUS_OK = "green";
        public final static String STATUS_ABSENT = "yellow";
        public final static String STATUS_BROKEN = "red";
        public final static String STATUS_CUTOFF = "gray";

        private String status;
        private String address;
        private Boolean compatible;
        private final String componentId;
        private final Map<String, Object> information;
        private final Date reachedTimestamp;
        private Date updatedTimestamp;
        private Date updatedAMQPTimestamp;
        private Date updatedHTTPTimestamp;

        @OpflowFieldExclude
        private long keepInTouchDuration;
        private String keepInTouchTime;
        @OpflowFieldExclude
        private long losingTouchDuration;
        private String losingTouchTime;

        public Manifest(String componentId) {
            this.componentId = componentId;
            this.information = new LinkedHashMap<>();
            this.reachedTimestamp = new Date();
        }

        public void touch() {
            this.updatedTimestamp = new Date();
        }
        
        public Manifest refresh(long keepAliveTimeout) {
            // update the timestamps
            this.keepInTouchDuration = updatedTimestamp.getTime() - reachedTimestamp.getTime();
            this.keepInTouchTime = OpflowDateTime.printElapsedTime(this.keepInTouchDuration);
            this.losingTouchDuration = (new Date()).getTime() - updatedTimestamp.getTime();
            this.losingTouchTime = OpflowDateTime.printElapsedTime(this.losingTouchDuration);
            // update the compatible field
            if (compatible == null) {
                Object version = information.get("AMQP_PROTOCOL_VERSION");
                if (version != null) {
                    if (version.equals(CONST.AMQP_PROTOCOL_VERSION)) {
                        this.compatible = true;
                    } else {
                        if (version.equals("0")) {
                            this.compatible = CONST.LEGACY_HEADER_ENABLED;
                        } else {
                            this.compatible = false;
                        }
                    }
                }
            }
            // update the status of the manifest
            if (losingTouchDuration > keepAliveTimeout * 3) {
                this.status = STATUS_CUTOFF;
            } else if (losingTouchDuration > keepAliveTimeout * 2) {
                this.status = STATUS_BROKEN;
            } else if (losingTouchDuration > keepAliveTimeout) {
                this.status = STATUS_ABSENT;
            } else {
                this.status = STATUS_OK;
            }
            return this;
        }
        
        public boolean expired() {
            return STATUS_CUTOFF.equals(this.status);
        }
    }
}
