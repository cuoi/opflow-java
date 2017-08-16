package com.devebot.opflow.bdd;

import com.devebot.opflow.bdd.steps.OpflowCommonSteps;
import com.devebot.opflow.bdd.steps.OpflowRpcMasterSteps;
import com.devebot.opflow.bdd.steps.OpflowRpcWorkerSteps;
import java.util.Arrays;
import java.util.List;
import org.jbehave.core.io.CodeLocations;
import org.jbehave.core.io.StoryFinder;
import org.jbehave.core.steps.InstanceStepsFactory;
import org.jbehave.core.steps.InjectableStepsFactory;

/**
 *
 * @author drupalex
 */
public class OpflowRpcStories extends OpflowEmbedder {

    @Override
    public InjectableStepsFactory stepsFactory() {
        return new InstanceStepsFactory(configuration(),
                new OpflowCommonSteps(),
                new OpflowRpcMasterSteps(),
                new OpflowRpcWorkerSteps());
    }
     
    @Override
    protected List<String> storyPaths() {
        String codeLocation = CodeLocations.codeLocationFromClass(this.getClass()).getFile();
        return new StoryFinder().findPaths(codeLocation, Arrays.asList("**/rpc-*.story"), Arrays.asList(""), "file:" + codeLocation);
    }
}
