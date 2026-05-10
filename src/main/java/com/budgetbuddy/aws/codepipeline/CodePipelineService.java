package com.budgetbuddy.aws.codepipeline;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.codepipeline.CodePipelineClient;
import software.amazon.awssdk.services.codepipeline.model.GetPipelineStateRequest;
import software.amazon.awssdk.services.codepipeline.model.GetPipelineStateResponse;
import software.amazon.awssdk.services.codepipeline.model.ListPipelineExecutionsRequest;
import software.amazon.awssdk.services.codepipeline.model.ListPipelineExecutionsResponse;
import software.amazon.awssdk.services.codepipeline.model.ListPipelinesRequest;
import software.amazon.awssdk.services.codepipeline.model.ListPipelinesResponse;
import software.amazon.awssdk.services.codepipeline.model.PipelineExecutionSummary;
import software.amazon.awssdk.services.codepipeline.model.PipelineSummary;
import software.amazon.awssdk.services.codepipeline.model.StartPipelineExecutionRequest;
import software.amazon.awssdk.services.codepipeline.model.StartPipelineExecutionResponse;

/** AWS CodePipeline Integration Service Provides CI/CD pipeline monitoring and management */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Service
public class CodePipelineService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CodePipelineService.class);

    private final CodePipelineClient codePipelineClient;

    public CodePipelineService(final CodePipelineClient codePipelineClient) {
        this.codePipelineClient = codePipelineClient;
    }

    /** Get pipeline status */
    public String getPipelineStatus(final String pipelineName) {
        try {
            final GetPipelineStateResponse response =
                    codePipelineClient.getPipelineState(
                            GetPipelineStateRequest.builder().name(pipelineName).build());

            return response.stageStates().stream()
                    .filter(
                            stage ->
                                    !"Succeeded".equals(stage.latestExecution().statusAsString()))
                    .findFirst()
                    .map(stage -> stage.latestExecution().statusAsString())
                    .orElse("Succeeded");
        } catch (Exception e) {
            LOGGER.error("Failed to get pipeline status: {}", e.getMessage());
            return "Unknown";
        }
    }

    /** List all pipelines */
    public List<PipelineSummary> listPipelines() {
        try {
            final ListPipelinesResponse response =
                    codePipelineClient.listPipelines(ListPipelinesRequest.builder().build());

            return response.pipelines();
        } catch (Exception e) {
            LOGGER.error("Failed to list pipelines: {}", e.getMessage());
            return List.of();
        }
    }

    /** Get pipeline execution history */
    public List<PipelineExecutionSummary> getPipelineExecutionHistory(
            final String pipelineName, final int maxResults) {
        try {
            final ListPipelineExecutionsResponse response =
                    codePipelineClient.listPipelineExecutions(
                            ListPipelineExecutionsRequest.builder()
                                    .pipelineName(pipelineName)
                                    .maxResults(maxResults)
                                    .build());

            return response.pipelineExecutionSummaries();
        } catch (Exception e) {
            LOGGER.error("Failed to get pipeline execution history: {}", e.getMessage());
            return List.of();
        }
    }

    /** Start pipeline execution */
    public String startPipelineExecution(final String pipelineName) {
        try {
            final StartPipelineExecutionResponse response =
                    codePipelineClient.startPipelineExecution(
                            StartPipelineExecutionRequest.builder().name(pipelineName).build());

            LOGGER.info(
                    "Pipeline execution started: {} - Execution ID: {}",
                    pipelineName,
                    response.pipelineExecutionId());
            return response.pipelineExecutionId();
        } catch (Exception e) {
            LOGGER.error("Failed to start pipeline execution: {}", e.getMessage());
            return null;
        }
    }
}
