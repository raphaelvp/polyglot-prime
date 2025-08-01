package org.techbd.service.csv;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;
import org.techbd.config.Configuration;
import org.techbd.config.Constants;
import org.techbd.config.CoreUdiPrimeJpaConfig;
import org.techbd.config.CsvProcessingState;
import org.techbd.config.Nature;
import org.techbd.config.Origin;
import org.techbd.config.SourceType;
import org.techbd.config.State;
import org.techbd.service.csv.engine.CsvOrchestrationEngine;
import org.techbd.service.dataledger.CoreDataLedgerApiClient;
import org.techbd.service.dataledger.CoreDataLedgerApiClient.DataLedgerPayload;
import org.techbd.udi.auto.jooq.ingress.routines.RegisterInteractionCsvRequest;
import org.techbd.util.SystemDiagnosticsLogger;
import org.techbd.util.fhir.CoreFHIRUtil;

import com.fasterxml.jackson.databind.JsonNode;

@Service
public class CsvService {

        private final CsvOrchestrationEngine engine;
        private static final Logger LOG = LoggerFactory.getLogger(CsvService.class);
        private final CsvBundleProcessorService csvBundleProcessorService;
        private final CoreDataLedgerApiClient coreDataLedgerApiClient;
        private final CoreUdiPrimeJpaConfig coreUdiPrimeJpaConfig;
        private final TaskExecutor asyncTaskExecutor;

        public CsvService(
                        final CsvOrchestrationEngine engine,
                        final CsvBundleProcessorService csvBundleProcessorService,
                        final CoreDataLedgerApiClient coreDataLedgerApiClient,
                        final CoreUdiPrimeJpaConfig coreUdiPrimeJpaConfig,
                        @Qualifier("asyncTaskExecutor") final TaskExecutor asyncTaskExecutor) {
                this.engine = engine;
                this.csvBundleProcessorService = csvBundleProcessorService;
                this.coreDataLedgerApiClient = coreDataLedgerApiClient;
                this.coreUdiPrimeJpaConfig = coreUdiPrimeJpaConfig;
                this.asyncTaskExecutor = asyncTaskExecutor;
        }

        public Object validateCsvFile(final MultipartFile file, final Map<String, Object> requestParameters,
                        Map<String, Object> resonseParameters) throws Exception {
                final var zipFileInteractionId = (String) requestParameters.get(Constants.MASTER_INTERACTION_ID);
                LOG.info("CsvService validateCsvFile BEGIN zip File interaction id  : {} tenant id : {}",
                                zipFileInteractionId, requestParameters.get(Constants.TENANT_ID));
                CsvOrchestrationEngine.OrchestrationSession session = null;
                try {
                        final var dslContext = coreUdiPrimeJpaConfig.dsl();
                        final var jooqCfg = dslContext.configuration();
                        saveArchiveInteraction(zipFileInteractionId, jooqCfg, requestParameters, file,
                                        CsvProcessingState.RECEIVED);
                        session = engine.session()
                                        .withMasterInteractionId(zipFileInteractionId)
                                        .withSessionId(UUID.randomUUID().toString())
                                        .withTenantId((String) requestParameters.get(Constants.TENANT_ID))
                                        .withFile(file)
                                        .withRequestParameters(requestParameters)
                                        .build();
                        engine.orchestrate(session);
                        LOG.info("CsvService validateCsvFile END zip File interaction id  : {} tenant id : {}",
                                        zipFileInteractionId, requestParameters.get(Constants.TENANT_ID));
                        return session.getValidationResults();
                } finally {
                        if (null == session) {
                                engine.clear(session);
                        }
                }
        }

        private void saveArchiveInteraction(String zipFileInteractionId,
                        org.jooq.Configuration jooqCfg,
                        CsvProcessingState state) {
                LOG.info("CsvService saveArchiveInteraction - STATUS UPDATE ONLY | zipFileInteractionId: {}, newState: {}",
                                zipFileInteractionId, state.name());
                final var updateRIHR = new RegisterInteractionCsvRequest();
                try {
                        updateRIHR.setPInteractionId(zipFileInteractionId);
                        updateRIHR.setPCsvStatus(state.name());
                        final var start = Instant.now();
                        final var execResult = updateRIHR.execute(jooqCfg);
                        final var end = Instant.now();
                        final JsonNode responseFromDB = updateRIHR.getReturnValue();
                        final Map<String, Object> responseAttributes = CoreFHIRUtil.extractFields(responseFromDB);
                        LOG.info(
                                        "CsvService - STATUS UPDATE END | zipFileInteractionId: {}, newState: {}, timeTaken: {} ms, error: {}, hub_nexus_interaction_id: {}{}",
                                        zipFileInteractionId,
                                        state.name(),
                                        Duration.between(start, end).toMillis(),
                                        responseAttributes.getOrDefault(Constants.KEY_ERROR, "N/A"),
                                        responseAttributes.getOrDefault(Constants.KEY_HUB_NEXUS_INTERACTION_ID, "N/A"),
                                        execResult);
                } catch (Exception e) {
                        LOG.error("ERROR:: Status update failed for interactionId: {}, state: {}",
                                        zipFileInteractionId, state.name(), e);
                }
        }

        private void saveArchiveInteraction(String zipFileInteractionId, final org.jooq.Configuration jooqCfg,
                        final Map<String, Object> requestParameters,
                        final MultipartFile file, final CsvProcessingState state) {
                final var tenantId = requestParameters.get(Constants.TENANT_ID);
                LOG.info("CsvService saveArchiveInteraction  -BEGIN zipFileInteractionId  : {} tenant id : {}",
                                zipFileInteractionId, tenantId);
                final var forwardedAt = OffsetDateTime.now();
                final var initRIHR = new RegisterInteractionCsvRequest();
                try {
                        initRIHR.setPOrigin(null == requestParameters.get(Constants.ORIGIN) ? Origin.HTTP.name()
                                        : (String) requestParameters.get(Constants.ORIGIN));
                        initRIHR.setPInteractionId(zipFileInteractionId);
                        initRIHR.setPInteractionKey((String) requestParameters.get(Constants.REQUEST_URI));
                        initRIHR.setPNature((JsonNode) Configuration.objectMapper.valueToTree(
                                        Map.of("nature", Nature.ORIGINAL_CSV_ZIP_ARCHIVE.getDescription(), "tenant_id",
                                                        tenantId)));
                        initRIHR.setPFromState(State.NONE.name());
                        initRIHR.setPToState(State.NONE.name());
                        initRIHR.setPContentType(MimeTypeUtils.APPLICATION_JSON_VALUE);
                        initRIHR.setPCsvZipFileContent(file.getBytes());
                        initRIHR.setPCsvZipFileName(file.getOriginalFilename());
                        initRIHR.setPCreatedAt(forwardedAt);
                        initRIHR.setPCsvStatus(state.name());
                        final InetAddress localHost = InetAddress.getLocalHost();
                        final String ipAddress = localHost.getHostAddress();
                        initRIHR.setPClientIpAddress(ipAddress);
                        initRIHR.setPUserAgent((String) requestParameters.get(Constants.USER_AGENT));
                        initRIHR.setPCreatedBy(CsvService.class.getName());
                        final var provenance = "%s.saveArchiveInteraction".formatted(CsvService.class.getName());
                        initRIHR.setPProvenance(provenance);
                        initRIHR.setPCsvGroupId(zipFileInteractionId);
                        setUserDetails(initRIHR, requestParameters);
                        final var start = Instant.now();
                        final var execResult = initRIHR.execute(jooqCfg);
                        final var end = Instant.now();
                        final JsonNode responseFromDB = initRIHR.getReturnValue();
                        final Map<String, Object> responseAttributes = CoreFHIRUtil.extractFields(responseFromDB);
                        LOG.info(
                                        "CsvServoce - saveArchiveInteraction END | zipFileInteractionId: {}, tenantId: {}, timeTaken: {} ms, error: {}, hub_nexus_interaction_id: {}{}",
                                        zipFileInteractionId,
                                        tenantId,
                                        Duration.between(start, end).toMillis(),
                                        responseAttributes.getOrDefault(Constants.KEY_ERROR, "N/A"),
                                        responseAttributes.getOrDefault(Constants.KEY_HUB_NEXUS_INTERACTION_ID, "N/A"),
                                        execResult);
                } catch (final Exception e) {
                        LOG.error("ERROR:: REGISTER State NONE CALL for interaction id : {} tenant id : {}"
                                        + initRIHR.getName() + " initRIHR error", zipFileInteractionId,
                                        tenantId,
                                        e);
                }
        }

        private void setUserDetails(RegisterInteractionCsvRequest rihr, Map<String, Object> requestParameters) {
                rihr.setPUserName(null == requestParameters.get(Constants.USER_NAME) ? Constants.DEFAULT_USER_NAME
                                : (String) requestParameters.get(Constants.USER_NAME));
                rihr.setPUserId(null == requestParameters.get(Constants.USER_ID) ? Constants.DEFAULT_USER_ID
                                : (String) requestParameters.get(Constants.USER_ID));
                rihr.setPUserSession(UUID.randomUUID().toString());
                rihr.setPUserRole(null == requestParameters.get(Constants.USER_ROLE) ? Constants.DEFAULT_USER_ROLE
                                : (String) requestParameters.get(Constants.USER_ROLE));
        }

        private void auditInitialReceipt(
                        String interactionId,
                        String provenance,
                        Map<String, Object> requestParams,
                        MultipartFile file,
                        org.jooq.Configuration jooqCfg) {

                var dataLedgerPayload = DataLedgerPayload.create(
                                CoreDataLedgerApiClient.Actor.TECHBD.getValue(),
                                CoreDataLedgerApiClient.Action.RECEIVED.getValue(),
                                CoreDataLedgerApiClient.Actor.TECHBD.getValue(),
                                interactionId);

                coreDataLedgerApiClient.processRequest(dataLedgerPayload, interactionId, provenance,
                                SourceType.CSV.name(), null);
                saveArchiveInteraction(interactionId, jooqCfg, requestParams, file, CsvProcessingState.RECEIVED);
        }
        private List<Object> processSync(
        String interactionId,
        String tenantId,
        Map<String, Object> requestParams,
        Map<String, Object> responseParams,
        MultipartFile file,
        org.jooq.Configuration jooqCfg,
        long start) throws Exception {

    CsvOrchestrationEngine.OrchestrationSession session = null;

    try {
        saveArchiveInteraction(interactionId, jooqCfg, CsvProcessingState.PROCESSING_STARTED);

        session = engine.session()
                .withMasterInteractionId(interactionId)
                .withSessionId(UUID.randomUUID().toString())
                .withTenantId(tenantId)
                .withGenerateBundle(true)
                .withFile(file)
                .withRequestParameters(requestParams)
                .build();

        engine.orchestrate(session);

        List<Object> result = csvBundleProcessorService.processPayload(
                interactionId,
                session.getPayloadAndValidationOutcomes(),
                session.getFilesNotProcessed(),
                requestParams,
                responseParams,
                tenantId,
                file.getOriginalFilename(),
                (String) requestParams.get(Constants.BASE_FHIR_URL));

        saveArchiveInteraction(interactionId, jooqCfg, CsvProcessingState.PROCESSING_COMPLETED);
        LOG.info("Synchronous processing completed for zipFileInteractionId: {}", interactionId);
        return result;
    } catch (Exception ex) {
        LOG.error("Synchronous processing failed for zipFileInteractionId: {}. Reason: {}", interactionId, ex.getMessage(), ex);
        saveArchiveInteraction(interactionId, jooqCfg, CsvProcessingState.PROCESSING_FAILED);
        SystemDiagnosticsLogger.logResourceStats(interactionId, coreUdiPrimeJpaConfig.udiPrimaryDataSource(), asyncTaskExecutor);
        throw ex;
    } finally {
        engine.clear(session);
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        LOG.info("Synchronous cleanup complete for zipFileInteractionId: {}, Total time taken: {} ms", interactionId, durationMs);
    }
}
private void processAsync(
        String interactionId,
        String tenantId,
        Map<String, Object> requestParams,
        Map<String, Object> responseParams,
        MultipartFile file,
        org.jooq.Configuration jooqCfg,
        long start) {

    CompletableFuture.runAsync(() -> {
        CsvOrchestrationEngine.OrchestrationSession session = null;

        try {
            saveArchiveInteraction(interactionId, jooqCfg, CsvProcessingState.PROCESSING_STARTED);

            session = engine.session()
                    .withMasterInteractionId(interactionId)
                    .withSessionId(UUID.randomUUID().toString())
                    .withTenantId(tenantId)
                    .withGenerateBundle(true)
                    .withFile(file)
                    .withRequestParameters(requestParams)
                    .build();

            engine.orchestrate(session);

            csvBundleProcessorService.processPayload(
                    interactionId,
                    session.getPayloadAndValidationOutcomes(),
                    session.getFilesNotProcessed(),
                    requestParams,
                    responseParams,
                    tenantId,
                    file.getOriginalFilename(),
                    (String) requestParams.get(Constants.BASE_FHIR_URL));

            saveArchiveInteraction(interactionId, jooqCfg, CsvProcessingState.PROCESSING_COMPLETED);
            LOG.info("Asynchronous processing completed for zipFileInteractionId: {}", interactionId);
        } catch (Exception ex) {
            LOG.error("Asynchronous processing failed for zipFileInteractionId: {}. Reason: {}", interactionId, ex.getMessage(), ex);
            saveArchiveInteraction(interactionId, jooqCfg, CsvProcessingState.PROCESSING_FAILED);
            SystemDiagnosticsLogger.logResourceStats(interactionId, coreUdiPrimeJpaConfig.udiPrimaryDataSource(), asyncTaskExecutor);
        } finally {
            engine.clear(session);
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            LOG.info("Asynchronous cleanup complete for zipFileInteractionId: {}, Total time taken: {} ms", interactionId, durationMs);
        }
    }, asyncTaskExecutor);
}
private Map<String, Object> buildAsyncResponse(String interactionId) {
    Map<String, Object> response = new HashMap<>();
    response.put("status", "RECEIVED");
    response.put("message",
            "Your file has been received and is being processed. You can track the progress using the interaction ID provided below. Please refer to the Hub UI for detailed status updates.");
    response.put("zipFileInteractionId", interactionId);
    return response;
}
public List<Object> processZipFile(
        final MultipartFile file,
        final Map<String, Object> requestParameters,
        final Map<String, Object> responseParameters) throws Exception {

    final var zipFileInteractionId = (String) requestParameters.get(Constants.MASTER_INTERACTION_ID);
    final var tenantId = (String) requestParameters.get(Constants.TENANT_ID);
    final var provenance = "%s.processZipFile".formatted(CsvService.class.getName());
    final String isSync = String.valueOf(requestParameters.get(Constants.IMMEDIATE));
    final var dslContext = coreUdiPrimeJpaConfig.dsl();
    final var jooqCfg = dslContext.configuration();

    LOG.info("CsvService processZipFile - BEGIN zipFileInteractionId: {} tenantId: {} isSync: {}",
            zipFileInteractionId, tenantId, isSync);

    // Ledger + Initial Archive
    auditInitialReceipt(zipFileInteractionId, provenance, requestParameters, file, jooqCfg);

    long start = System.nanoTime();

    if ("true".equalsIgnoreCase(isSync)) {
        LOG.info("Starting synchronous processing for zipFileInteractionId: {}", zipFileInteractionId);
        return processSync(zipFileInteractionId, tenantId, requestParameters, responseParameters, file, jooqCfg, start);
    } else {
        LOG.info("Starting asynchronous processing for zipFileInteractionId: {}", zipFileInteractionId);
        processAsync(zipFileInteractionId, tenantId, requestParameters, responseParameters, file, jooqCfg, start);

        Map<String, Object> response = buildAsyncResponse(zipFileInteractionId);
        LOG.info("Returning interim async response for zipFileInteractionId: {} tenantId: {}", zipFileInteractionId, tenantId);
        return List.of(response);
    }
}

}
