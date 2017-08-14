package com.devebot.opflow.bdd.steps;

import com.devebot.opflow.OpflowHelper;
import com.devebot.opflow.OpflowMessage;
import com.devebot.opflow.OpflowRpcListener;
import com.devebot.opflow.OpflowRpcResponse;
import com.devebot.opflow.OpflowRpcWorker;
import com.devebot.opflow.OpflowUtil;
import com.devebot.opflow.exception.OpflowConstructorException;
import com.devebot.opflow.exception.OpflowOperationException;
import com.devebot.opflow.lab.FibonacciGenerator;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import org.jbehave.core.annotations.Given;
import org.jbehave.core.annotations.Named;
import org.jbehave.core.annotations.Then;
import org.jbehave.core.annotations.When;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author drupalex
 */

public class OpflowRpcWorkerSteps {
    private final static Logger LOG = LoggerFactory.getLogger(OpflowRpcWorkerSteps.class);
    
    private final Map<String, OpflowRpcWorker> workers = new HashMap<String, OpflowRpcWorker>();
    
    @Given("a RPC worker<$string>")
    public void createRpcWorker(@Named("workerName") String workerName) throws OpflowConstructorException {
        workers.put(workerName, OpflowHelper.createRpcWorker());
    }
    
    @Given("a RPC worker<$workerName> with properties file: '$propFile'")
    public void createRpcMaster(@Named("workerName") final String workerName, 
            @Named("propFile") final String propFile) throws OpflowConstructorException {
        workers.put(workerName, OpflowHelper.createRpcWorker(propFile));
    }
    
    @Given("a counter consumer in worker<$workerName>")
    public void consumeAllMessage(@Named("workerName") String workerName) {
        workers.get(workerName).process(new OpflowRpcListener() {
            @Override
            public Boolean processMessage(OpflowMessage message, OpflowRpcResponse response) throws IOException {
                if (LOG.isTraceEnabled()) LOG.trace("[+] Routine input: " + message.getContentAsString());
                return OpflowRpcListener.NEXT;
            }
        });
    }
    
    @Given("a EchoJsonObject consumer in worker<$workerName> with names '$names'")
    public void consumeEchoJsonObject(@Named("names") String names, @Named("workerName") String workerName) {
        workers.get(workerName).process(splitString(names), new OpflowRpcListener() {
            @Override
            public Boolean processMessage(OpflowMessage message, OpflowRpcResponse response) throws IOException {
                try {
                    String msg = message.getContentAsString();
                    if (LOG.isTraceEnabled()) LOG.trace("[+] EchoJsonObject received: '" + msg + "'");
                    
                    String result = msg;
                    
                    // MANDATORY
                    response.emitCompleted(result);
                } catch (final Exception ex) {
                    String errmsg = OpflowUtil.buildJson(new OpflowUtil.MapListener() {
                        @Override
                        public void transform(Map<String, Object> opts) {
                            opts.put("exceptionClass", ex.getClass().getName());
                            opts.put("exceptionMessage", ex.getMessage());
                        }
                    });
                    if (LOG.isErrorEnabled()) LOG.error("[-] EchoJsonObject error message: " + errmsg);
                    
                    // MANDATORY
                    response.emitFailed(errmsg);
                }
                
                return null;
            }
        });
    }
    
    @Given("a FibonacciGenerator consumer with names '$names' in worker<$workerName>")
    public void consumeFibonacciGenerator(@Named("names") String names, @Named("workerName") String workerName) {
        workers.get(workerName).process(splitString(names), new OpflowRpcListener() {
            @Override
            public Boolean processMessage(OpflowMessage message, OpflowRpcResponse response) throws IOException {
                try {
                    String msg = message.getContentAsString();
                    if (LOG.isTraceEnabled()) LOG.trace("[+] Fibonacci received: '" + msg + "'");

                    // OPTIONAL
                    response.emitStarted();

                    Map<String, Object> jsonMap = OpflowUtil.jsonStringToMap(msg);
                    int number = ((Double) jsonMap.get("number")).intValue();
                    if (number < 0) throw new OpflowOperationException("number should be positive");
                    if (number > 40) throw new OpflowOperationException("number exceeding limit (40)");
                    
                    FibonacciGenerator fibonacci = new FibonacciGenerator(number);

                    // OPTIONAL
                    while(fibonacci.next()) {
                        FibonacciGenerator.Result r = fibonacci.result();
                        response.emitProgress(r.getStep(), r.getNumber());
                    }

                    String result = OpflowUtil.jsonObjToString(fibonacci.result());
                    if (LOG.isTraceEnabled()) LOG.trace("[-] Fibonacci finished with: '" + result + "'");

                    // MANDATORY
                    response.emitCompleted(result);
                } catch (final Exception ex) {
                    String errmsg = OpflowUtil.buildJson(new OpflowUtil.MapListener() {
                        @Override
                        public void transform(Map<String, Object> opts) {
                            opts.put("exceptionClass", ex.getClass().getName());
                            opts.put("exceptionMessage", ex.getMessage());
                        }
                    });
                    if (LOG.isErrorEnabled()) LOG.error("[-] Error message: " + errmsg);
                    
                    // MANDATORY
                    response.emitFailed(errmsg);
                }
                
                return null;
            }
        });
    }
    
    @When("I close RPC worker<$workerName>")
    public void closeRpcMaster(@Named("workerName") String workerName) {
        workers.get(workerName).close();
    }
    
    @Then("the RPC worker<$workerName> connection is '$status'")
    public void checkRpcWorker(@Named("workerName") String workerName, @Named("status") String status) {
        OpflowRpcWorker.State state = workers.get(workerName).check();
        List<String> collection = Lists.newArrayList("opened", "closed");
        assertThat(collection, hasItem(status));
        if ("opened".equals(status)) {
            assertThat(OpflowRpcWorker.State.CONNECTION_OPENED, equalTo(state.getConnectionState()));
        } else if ("closed".equals(status)) {
            assertThat(OpflowRpcWorker.State.CONNECTION_CLOSED, equalTo(state.getConnectionState()));
        }
    }
    
    private static String[] splitString(String str) {
        return (str == null) ? null : str.split(",");
    }
}
