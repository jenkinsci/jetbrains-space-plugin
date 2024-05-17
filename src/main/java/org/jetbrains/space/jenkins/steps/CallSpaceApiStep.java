package org.jetbrains.space.jenkins.steps;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.ListBoxModel;
import io.ktor.http.HttpMethod;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.verb.POST;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>Pipeline step for calling JetBrains SpaceCode API.</p>
 * <p>Accepts HTTP method, path (starting with `/api/http`, without host) and optionally request body.</p>
 * <p>Returns an instance of {@code com.fasterxml.jackson.databind.JsonNode} or null if no response body is present. </p>
 * <p>Takes care of the proper authentication using SpaceCode connection specified explicitly or obtained from buid trigger or code checkout settings.</p>
 *
 * <p>Sample usage in the pipeline script:</p>
 * <pre>{@code
 *     script {
 *          def result = callSpaceApi(httpMethod: 'GET', requestUrl: '/api/http/applications/me')
 *          echo result["name"].asText()
 *          echo result["createdAt"]["iso"].asText()
 *     }
 * }</pre>
 *
 * @see <a href="https://www.jetbrains.com/help/space/api.html">JetBrains SpaceCode API Documentation</a>
 */
public class CallSpaceApiStep extends Step {

    private final String httpMethod;
    private final String requestUrl;
    private final @Nullable String requestBody;

    @DataBoundConstructor
    public CallSpaceApiStep(String httpMethod, String requestUrl, @Nullable String requestBody) {
        this.httpMethod = (httpMethod != null) ? HttpMethod.Companion.parse(httpMethod).getValue() : HttpMethod.Companion.getGet().getValue();
        this.requestUrl = requestUrl;
        this.requestBody = (requestBody != null && !requestBody.isBlank()) ? requestBody : null;
    }

    @Override
    public StepExecution start(StepContext context) {
        return CallSpaceApiStepExecution.Companion.start(this, context);
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getRequestUrl() {
        return requestUrl;
    }

    public @Nullable String getRequestBody() {
        return requestBody;
    }

    @SuppressWarnings("unused")
    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public @NotNull String getDisplayName() {
            return "Make a call to JetBrains SpaceCode HTTP API";
        }

        @Override
        public String getFunctionName() {
            return "callSpaceApi";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return new HashSet<>(Arrays.asList(Run.class, TaskListener.class));
        }

        @POST
        // lgtm[jenkins/no-permission-check]
        public ListBoxModel doFillHttpMethodItems() {
            return HttpMethod.Companion.getDefaultMethods().stream()
                    .map(method -> new ListBoxModel.Option(method.getValue()))
                    .collect(Collectors.toCollection(ListBoxModel::new));
        }
    }
}
