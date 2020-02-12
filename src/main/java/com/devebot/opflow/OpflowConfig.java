package com.devebot.opflow;

import com.devebot.opflow.OpflowLogTracer.Level;
import com.devebot.opflow.supports.OpflowJsonTool;
import com.devebot.opflow.exception.OpflowBootstrapException;
import com.devebot.opflow.supports.OpflowEnvTool;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.parser.ParserException;
import org.yaml.snakeyaml.scanner.ScannerException;

/**
 *
 * @author acegik
 */
public class OpflowConfig {
    public final static String DEFAULT_CONFIGURATION_KEY = "opflow.configuration";
    public final static String DEFAULT_CONFIGURATION_ENV = "OPFLOW_CONFIGURATION";
    public final static String DEFAULT_CONFIGURATION_FILE = "opflow.properties";

    private final static Logger LOG = LoggerFactory.getLogger(OpflowConfig.class);
    private final static OpflowLogTracer LOG_TRACER = OpflowLogTracer.ROOT.copy();
    
    public interface Loader {
        public Map<String, Object> loadConfiguration() throws OpflowBootstrapException;
    }
    
    public static class LoaderImplRpcMaster implements OpflowConfig.Loader {
        
        private Map<String, Object> config;
        private final String configFile;
        private final boolean useDefaultFile;
        
        public LoaderImplRpcMaster(Map<String, Object> config, String configFile, boolean useDefaultFile) {
            this.config = config;
            this.configFile = configFile;
            this.useDefaultFile = useDefaultFile;
        }
        
        @Override
        public Map<String, Object> loadConfiguration() throws OpflowBootstrapException {
            config = OpflowConfig.loadConfiguration(config, configFile, useDefaultFile);
            Map<String, Object> params = new HashMap<>();

            String[] handlerPath = new String[] {"opflow", "master"};
            extractEngineParameters(params, config, handlerPath);
            Map<String, Object> handlerNode = getChildMapByPath(config, handlerPath);

            OpflowUtil.copyParameters(params, handlerNode, new String[] {
                "responseName",
                "responseQueueSuffix",
                "responseDurable",
                "responseExclusive",
                "responseAutoDelete",
                "prefetchCount",
            });

            transformParameters(params);
            return params;
        }
    }
    
    public static class LoaderImplRpcWorker implements OpflowConfig.Loader {
        
        private Map<String, Object> config;
        private final String configFile;
        private final boolean useDefaultFile;
        
        public LoaderImplRpcWorker(Map<String, Object> config, String configFile, boolean useDefaultFile) {
            this.config = config;
            this.configFile = configFile;
            this.useDefaultFile = useDefaultFile;
        }
        
        @Override
        public Map<String, Object> loadConfiguration() throws OpflowBootstrapException {
            config = OpflowConfig.loadConfiguration(config, configFile, useDefaultFile);
            Map<String, Object> params = new HashMap<>();

            String[] handlerPath = new String[] {"opflow", "worker"};
            extractEngineParameters(params, config, handlerPath);
            Map<String, Object> handlerNode = getChildMapByPath(config, handlerPath);
            Map<String, Object> opflowNode = getChildMapByPath(config, new String[] {"opflow"});

            OpflowUtil.copyParameters(params, handlerNode, new String[] {
                "operatorName",
                "responseName",
                "prefetchCount",
            });

            if (handlerNode.get("operatorName") == null) {
                params.put("operatorName", opflowNode.get("queueName"));
            }

            transformParameters(params);
            return params;
        }
    }
    
    public static class LoaderImplPubsubHandler implements OpflowConfig.Loader {
        
        private Map<String, Object> config;
        private final String configFile;
        private final boolean useDefaultFile;
        
        public LoaderImplPubsubHandler(Map<String, Object> config, String configFile, boolean useDefaultFile) {
            this.config = config;
            this.configFile = configFile;
            this.useDefaultFile = useDefaultFile;
        }
        
        @Override
        public Map<String, Object> loadConfiguration() throws OpflowBootstrapException {
            config = OpflowConfig.loadConfiguration(config, configFile, useDefaultFile);
            Map<String, Object> params = new HashMap<>();

            String[] handlerPath = new String[] {"opflow", "pubsub"};
            extractEngineParameters(params, config, handlerPath);
            Map<String, Object> handlerNode = getChildMapByPath(config, handlerPath);
            Map<String, Object> opflowNode = getChildMapByPath(config, new String[] {"opflow"});

            OpflowUtil.copyParameters(params, handlerNode, new String[] {
                "subscriberName",
                "recyclebinName",
                "prefetchCount",
                "subscriberLimit",
                "redeliveredLimit",
            });

            if (handlerNode.get("subscriberName") == null) {
                params.put("subscriberName", opflowNode.get("queueName"));
            }

            transformParameters(params);
            return params;
        }
    }
    
    public static class LoaderImplCommander implements OpflowConfig.Loader {
        
        private Map<String, Object> config;
        private final String configFile;
        private final boolean useDefaultFile;
        
        public LoaderImplCommander(Map<String, Object> config, String configFile, boolean useDefaultFile) {
            this.config = config;
            this.configFile = configFile;
            this.useDefaultFile = useDefaultFile;
        }
        
        @Override
        public Map<String, Object> loadConfiguration() throws OpflowBootstrapException {
            config = OpflowConfig.loadConfiguration(config, configFile, useDefaultFile);

            Map<String, Object> params = new HashMap<>();
            String[] componentPath = new String[] {"opflow", "commander", ""};
            for(String componentName:OpflowCommander.ALL_BEAN_NAMES) {
                componentPath[2] = componentName;
                Map<String, Object> componentCfg = new HashMap<>();
                Map<String, Object> componentNode;
                if (OpflowCommander.SERVICE_BEAN_NAMES.contains(componentName)) {
                    extractEngineParameters(componentCfg, config, componentPath);
                    componentNode = getChildMapByPath(config, componentPath);
                } else {
                    componentNode = getChildMapByPath(config, componentPath, false);
                }
                componentCfg.put("enabled", componentNode.get("enabled"));
                if ("reqExtractor".equals(componentName)) {
                    OpflowUtil.copyParameters(componentCfg, componentNode, new String[] {
                        "getRequestIdClassName",
                        "getRequestIdMethodName"
                    });
                }
                if ("restrictor".equals(componentName)) {
                    OpflowUtil.copyParameters(componentCfg, componentNode, new String[] {
                        "pauseEnabled",
                        "pauseTimeout",
                        "semaphoreEnabled",
                        "semaphoreLimit",
                        "semaphoreTimeout"
                    });
                }
                if ("rpcMaster".equals(componentName)) {
                    OpflowUtil.copyParameters(componentCfg, componentNode, new String[] {
                        "expiration",
                        "responseName",
                        "responseQueueSuffix",
                        "responseDurable",
                        "responseExclusive",
                        "responseAutoDelete",
                        "prefetchCount",
                        "monitorId",
                        "monitorEnabled",
                        "monitorInterval",
                        "monitorTimeout"
                    });
                }
                if ("rpcWatcher".equals(componentName)) {
                    OpflowUtil.copyParameters(componentCfg, componentNode, new String[] {
                        "interval"
                    });
                }
                if ("promExporter".equals(componentName)) {
                    OpflowUtil.copyParameters(componentCfg, componentNode, new String[] {
                        "host",
                        "ports"
                    });
                }
                if ("restServer".equals(componentName)) {
                    OpflowUtil.copyParameters(componentCfg, componentNode, new String[] {
                        "host",
                        "ports"
                    });
                }
                transformParameters(componentCfg);
                params.put(componentName, componentCfg);
            }
            return params;
        }
    }
    
    public static class LoaderImplServerlet implements OpflowConfig.Loader {
        
        private Map<String, Object> config;
        private final String configFile;
        private final boolean useDefaultFile;
        
        public LoaderImplServerlet(Map<String, Object> config, String configFile, boolean useDefaultFile) {
            this.config = config;
            this.configFile = configFile;
            this.useDefaultFile = useDefaultFile;
        }
        
        @Override
        public Map<String, Object> loadConfiguration() throws OpflowBootstrapException {
            config = OpflowConfig.loadConfiguration(config, configFile, useDefaultFile);
        
            Map<String, Object> params = new HashMap<>();
            String[] componentPath = new String[] {"opflow", "serverlet", ""};
            for(String componentName:OpflowServerlet.ALL_BEAN_NAMES) {
                componentPath[2] = componentName;
                Map<String, Object> componentCfg = new HashMap<>();
                extractEngineParameters(componentCfg, config, componentPath);
                Map<String, Object> componentNode = getChildMapByPath(config, componentPath);
                componentCfg.put("enabled", componentNode.get("enabled"));
                if ("rpcWorker".equals(componentName)) {
                    OpflowUtil.copyParameters(componentCfg, componentNode, new String[] {
                        "operatorName",
                        "responseName"
                    });
                }
                if ("subscriber".equals(componentName)) {
                    OpflowUtil.copyParameters(componentCfg, componentNode, new String[] {
                        "subscriberName",
                        "recyclebinName"
                    });
                }
                if ("promExporter".equals(componentName)) {
                    OpflowUtil.copyParameters(componentCfg, componentNode, new String[] {
                        "host",
                        "ports"
                    });
                }
                transformParameters(componentCfg);
                params.put(componentName, componentCfg);
            }

            return params;
        }
    }
    
    private static Object traverseMapByPath(Map<String, Object> source, String[] path) {
        Map<String, Object> point = source;
        Object value = null;
        for(String node : path) {
            if (point == null) return null;
            value = point.get(node);
            point = (value instanceof Map) ? (Map<String, Object>) value : null;
        }
        return value;
    }
    
    private static Map<String, Object> getChildMapByPath(Map<String, Object> source, String[] path) {
        return getChildMapByPath(source, path, true);
    }
    
    private static Map<String, Object> getChildMapByPath(Map<String, Object> source, String[] path, boolean disableByEmpty) {
        Object sourceObject = traverseMapByPath(source, path);
        if(sourceObject != null && sourceObject instanceof Map) {
            return (Map<String, Object>) sourceObject;
        }
        Map<String, Object> blank = new HashMap<>();
        if (disableByEmpty) {
            blank.put("enabled", false);
        }
        return blank;
    }
    
    private static void extractEngineParameters(Map<String, Object> target, Map<String, Object> source, String[] path) {
        List<Map<String, Object>> maps = new ArrayList<>(path.length);
        for(String node:path) {
            if (source.get(node) != null && source.get(node) instanceof Map) {
                source = (Map<String, Object>)source.get(node);
                maps.add(0, source);
            } else {
                break;
            }
        }
        for(String field: OpflowEngine.PARAMETER_NAMES) {
            Iterator<Map<String, Object>> iter = maps.iterator();
            while(iter.hasNext() && !target.containsKey(field)) {
                Map<String, Object> map = iter.next();
                if (map.containsKey(field)) {
                    target.put(field, map.get(field));
                }
            }
        }
    }
    
    private static final String[] BOOLEAN_FIELDS = new String[] {
        "enabled", "verbose", "automaticRecoveryEnabled", "topologyRecoveryEnabled", "strictMode",
        "monitorEnabled", "pauseEnabled", "semaphoreEnabled",
        "responseDurable", "responseExclusive", "responseAutoDelete"
    };

    private static final String[] STRING_FIELDS = new String[] {
        "responseQueueSuffix"
    };
    
    private static final String[] STRING_ARRAY_FIELDS = new String[] { "otherKeys" };
    
    private static final String[] INTEGER_FIELDS = new String[] {
        "port", "channelMax", "frameMax", "heartbeat", "networkRecoveryInterval", "semaphoreLimit",
        "prefetchCount", "subscriberLimit", "redeliveredLimit", "monitorInterval", "threadPoolSize"
    };
    
    private static final String[] INTEGER_ARRAY_FIELDS = new String[] { "ports" };
    
    private static final String[] INTEGER_RANGE_FIELDS = new String[] { "ports" };
    
    private static final String[] LONGINT_FIELDS = new String[] {
        "expiration", "interval", "monitorTimeout", "pauseTimeout", "semaphoreTimeout"
    };
    
    private static void transformParameters(Map<String, Object> params) {
        for(String key: params.keySet()) {
            if (OpflowUtil.arrayContains(BOOLEAN_FIELDS, key)) {
                if (params.get(key) instanceof String) {
                    params.put(key, Boolean.parseBoolean(params.get(key).toString()));
                }
            }
            if (OpflowUtil.arrayContains(STRING_ARRAY_FIELDS, key)) {
                if (params.get(key) instanceof String) {
                    params.put(key, OpflowUtil.splitByComma((String)params.get(key)));
                }
            }
            if (OpflowUtil.arrayContains(INTEGER_FIELDS, key)) {
                if (params.get(key) instanceof String) {
                    try {
                        params.put(key, Integer.parseInt(params.get(key).toString()));
                    } catch (NumberFormatException nfe) {
                        if (LOG_TRACER.ready(LOG, Level.TRACE)) LOG.trace(LOG_TRACER
                                .put("fieldName", key)
                                .text("transformParameters() - field is not an integer")
                                .stringify());
                        params.put(key, null);
                    }
                }
            }
            if (OpflowUtil.arrayContains(INTEGER_ARRAY_FIELDS, key)) {
                if (params.get(key) instanceof String) {
                    String intArrayStr = (String) params.get(key);
                    if (OpflowUtil.isIntegerArray(intArrayStr)) {
                        params.put(key, OpflowUtil.splitByComma(intArrayStr, Integer.class));
                    }
                }
            }
            if (OpflowUtil.arrayContains(INTEGER_RANGE_FIELDS, key)) {
                if (params.get(key) instanceof String) {
                    String intRangeStr = (String) params.get(key);
                    if (OpflowUtil.isIntegerRange(intRangeStr)) {
                        Integer[] range = OpflowUtil.getIntegerRange(intRangeStr);
                        if (range != null) {
                            params.put(key, new Integer[] { -1, range[0], range[1] });
                        }
                    }
                }
            }
            if (OpflowUtil.arrayContains(LONGINT_FIELDS, key)) {
                if (params.get(key) instanceof String) {
                    try {
                        params.put(key, Long.parseLong(params.get(key).toString()));
                    } catch (NumberFormatException nfe) {
                        if (LOG_TRACER.ready(LOG, Level.TRACE)) LOG.trace(LOG_TRACER
                                .put("fieldName", key)
                                .text("transformParameters() - field is not a longint")
                                .stringify());
                        params.put(key, null);
                    }
                }
            }
        }
    }
    
    public static Map<String, Object> loadConfiguration() throws OpflowBootstrapException {
        return loadConfiguration(null, null, true);
    }
    
    public static Map<String, Object> loadConfiguration(String configFile) throws OpflowBootstrapException {
        return loadConfiguration(null, configFile, configFile == null);
    }
    
    public static Map<String, Object> loadConfiguration(Map<String, Object> config, String configFile) throws OpflowBootstrapException {
        return loadConfiguration(config, configFile, configFile == null && config == null);
    }
    
    public static Map<String, Object> loadConfiguration(Map<String, Object> config, String configFile, boolean useDefaultFile) throws OpflowBootstrapException {
        try {
            if (config == null) {
                config = new HashMap<>();
            }
            if (configFile != null || useDefaultFile) {
                URL url = getConfigurationUrl(configFile);
                if (url != null) {
                    String ext = getConfigurationExtension(url);
                    if (LOG_TRACER.ready(LOG, Level.TRACE)) LOG.trace(LOG_TRACER
                            .put("configFile", url.getFile())
                            .put("extension", ext)
                            .text("load configuration file")
                            .stringify());
                    // load the configuration from YAML/Properties files
                    switch(ext) {
                        case "yaml":
                        case "yml":
                            Yaml yaml = new Yaml();
                            Map<String,Object> yamlConfig = (Map<String,Object>)yaml.load(url.openStream());
                            mergeConfiguration(config, yamlConfig);
                            break;
                        case "properties":
                            Properties propConfig = new Properties();
                            propConfig.load(url.openStream());
                            mergeConfiguration(config, propConfig);
                            break;

                    }
                    // merge the system properties to the configuration
                    mergeConfiguration(config, filterProperties(System.getProperties(), new String[] {
                        "opflow.commander",
                        "opflow.serverlet",
                        "opflow.publisher",
                        "opflow.subscriber",
                        "opflow.rpcMaster",
                        "opflow.rpcWorker",
                    }));
                } else {
                    configFile = (configFile != null) ? configFile : DEFAULT_CONFIGURATION_FILE;
                    throw new FileNotFoundException("configuration file '" + configFile + "' not found");
                }
            }
            if (LOG_TRACER.ready(LOG, Level.TRACE)) LOG.trace(LOG_TRACER
                    .put("YAML", OpflowJsonTool.toString(config))
                    .text("loaded properties content")
                    .stringify());
            return config;
        } catch (IOException exception) {
            throw new OpflowBootstrapException(exception);
        } catch (ParserException | ScannerException exception) {
            throw new OpflowBootstrapException(exception);
        } catch (MarkedYAMLException exception) {
            throw new OpflowBootstrapException(exception);
        }
    }
    
    private static URL getConfigurationUrl(String configFile) {
        URL url;
        String cfgFromSystem = OpflowEnvTool.instance.getSystemProperty(DEFAULT_CONFIGURATION_KEY, null);
        if (cfgFromSystem == null) {
            cfgFromSystem = configFile;
        }
        if (cfgFromSystem == null) {
            cfgFromSystem = OpflowEnvTool.instance.getEnvironVariable(DEFAULT_CONFIGURATION_ENV, null);
        }
        if (LOG_TRACER.ready(LOG, Level.TRACE)) LOG.trace(LOG_TRACER
                .put("configFile", cfgFromSystem)
                .text("detected configuration file")
                .stringify());
        if (cfgFromSystem == null) {
            url = OpflowUtil.getResource(DEFAULT_CONFIGURATION_FILE);
            if (LOG_TRACER.ready(LOG, Level.TRACE)) LOG.trace(LOG_TRACER
                .put("configFile", url)
                .text("default configuration url")
                .stringify());
        } else {
            try {
                url = new URL(cfgFromSystem);
            } catch (MalformedURLException ex) {
                // woa, the cfgFromSystem string is not a URL,
                // attempt to get the resource from the class path
                url = OpflowUtil.getResource(cfgFromSystem);
            }
        }
        if (LOG_TRACER.ready(LOG, Level.TRACE)) LOG.trace(LOG_TRACER
                .put("configFile", url)
                .text("final configuration url")
                .stringify());
        return url;
    }
    
    public static String getConfigurationExtension(URL url) {
        String filename = url.getFile();
        return filename.substring(filename.lastIndexOf(".") + 1);
    }
    
    public static Map<String, Object> mergeConfiguration(Map<String, Object> target, Map<String, Object> source) {
        if (target == null) target = new HashMap<>();
        if (source == null) return target;
        for (String key : source.keySet()) {
            if (source.get(key) instanceof Map && target.get(key) instanceof Map) {
                Map<String, Object> targetChild = (Map<String, Object>) target.get(key);
                Map<String, Object> sourceChild = (Map<String, Object>) source.get(key);
                target.put(key, mergeConfiguration(targetChild, sourceChild));
            } else {
                target.put(key, source.get(key));
            }
        }
        return target;
    }
    
    public static Properties filterProperties(Properties source, String filter) {
        return filterProperties(source, new String[] { filter });
    }
    
    public static Properties filterProperties(Properties source, String[] filters) {
        Properties result = new Properties();
        if (source == null) {
            return null;
        }
        if (filters == null || filters.length == 0) {
            return new Properties(source);
        }
        for(Map.Entry<Object, Object> entry: source.entrySet()) {
            String key = entry.getKey().toString();
            for (String filter : filters) {
                if (key.startsWith(filter)) {
                    result.put(key, source.get(key));
                    break;
                }
            }
        }
        return result;
    }
    
    public static Map<String, Object> mergeConfiguration(Map<String, Object> target, Properties props) {
        if (target == null) target = new HashMap<>();
        if (props == null) return target;
        Set<Object> keys = props.keySet();
        for(Object keyo:keys) {
            String key = (String) keyo;
            String[] path = key.split("\\.");
            if (path.length > 0) {
                Map<String, Object> current = target;
                for(int i=0; i<(path.length - 1); i++) {
                    if (!current.containsKey(path[i]) || !(current.get(path[i]) instanceof Map)) {
                        current.put(path[i], new HashMap<String, Object>());
                    }
                    current = (Map<String, Object>)current.get(path[i]);
                }
                current.put(path[path.length - 1], props.getProperty(key));
            }
        }
        return target;
    }
    
    public static Properties loadProperties() throws OpflowBootstrapException {
        Properties props = new Properties();
        URL url = OpflowUtil.getResource(DEFAULT_CONFIGURATION_FILE);
        try {
            if (url != null) props.load(url.openStream());
        } catch (IOException exception) {
            throw new OpflowBootstrapException(exception);
        }
        return props;
    }
    
    private static String getPropertiesAsString(Properties prop) {
        StringWriter writer = new StringWriter();
        prop.list(new PrintWriter(writer));
        return writer.getBuffer().toString();
    }
}
