package org.techbd.service.http.hub.prime.api;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.security.access.method.P;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.techbd.conf.Configuration;
import org.techbd.orchestrate.fhir.OrchestrationEngine;
import org.techbd.orchestrate.fhir.OrchestrationEngine.Device;
import org.techbd.service.http.Helpers;
import org.techbd.service.http.InteractionsFilter;
import org.techbd.service.http.hub.CustomRequestWrapper;
import org.techbd.service.http.hub.prime.AppConfig;
import org.techbd.udi.UdiPrimeJpaConfig;
import org.techbd.udi.auto.jooq.ingress.routines.RegisterInteractionHttpRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.oauth2.sdk.util.CollectionUtils;

import ca.uhn.fhir.validation.ResultSeverityEnum;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.annotation.Nonnull;
import jakarta.servlet.http.HttpServletRequest;
import reactor.core.publisher.Mono;

@Service
public class FHIRService {
        private static final Logger LOG = LoggerFactory.getLogger(FHIRService.class.getName());
        private final AppConfig appConfig;
        private final OrchestrationEngine engine = new OrchestrationEngine();
        private final UdiPrimeJpaConfig udiPrimeJpaConfig;

        public FHIRService(
                        final AppConfig appConfig, final UdiPrimeJpaConfig udiPrimeJpaConfig) {
                this.appConfig = appConfig;
                this.udiPrimeJpaConfig = udiPrimeJpaConfig;
        }

        public Map<String, Map<String, Object>> processBundle(final @RequestBody @Nonnull String payload,
                        String tenantId,
                        String fhirProfileUrlParam, String fhirProfileUrlHeader, String uaValidationStrategyJson,
                        String customDataLakeApi,
                        String dataLakeApiContentType,
                        String healthCheck,
                        boolean isSync,
                        boolean includeRequestInOutcome,
                        boolean includeIncomingPayloadInDB,
                        HttpServletRequest request, String provenance) {
                final var fhirProfileUrl = (fhirProfileUrlParam != null) ? fhirProfileUrlParam
                                : (fhirProfileUrlHeader != null) ? fhirProfileUrlHeader
                                                : appConfig.getDefaultSdohFhirProfileUrl();
                final var immediateResult = validate(request, payload, fhirProfileUrl, uaValidationStrategyJson,
                                includeRequestInOutcome);
                final var result = Map.of("OperationOutcome", immediateResult);

                // Check for the X-TechBD-HealthCheck header
                if ("true".equals(healthCheck)) {
                        LOG.info("%s is true, skipping DataLake submission."
                                        .formatted(AppConfig.Servlet.HeaderName.Request.HEALTH_CHECK_HEADER));
                        return result; // Return without proceeding to DataLake submission
                }
                sendToScoringEngine(request, customDataLakeApi, tenantId, payload, provenance,
                                immediateResult, includeIncomingPayloadInDB, dataLakeApiContentType, result);
                return result;
        }

        private Map<String, Object> validate(HttpServletRequest request, String payload, String fhirProfileUrl,
                        String uaValidationStrategyJson,
                        boolean includeRequestInOutcome) {

                LOG.info("Getting structure definition Urls from config - Before: ");
                final var structureDefintionUrls = appConfig.getStructureDefinitionsUrls();
                LOG.info("Getting structure definition Urls from config - After : ", structureDefintionUrls);
                final var valueSetUrls = appConfig.getValueSetUrls();
                LOG.info(" Total value system URLS  in config: ", null != valueSetUrls ? valueSetUrls.size() : 0);
                final var codeSystemUrls = appConfig.getCodeSystemUrls();
                LOG.info(" Total code system URLS  in config: ", null != codeSystemUrls ? codeSystemUrls.size() : 0);
                final var sessionBuilder = engine.session()
                                .onDevice(Device.createDefault())
                                .withPayloads(List.of(payload))
                                .withFhirProfileUrl(fhirProfileUrl)
                                .withFhirStructureDefinitionUrls(structureDefintionUrls)
                                .withFhirCodeSystemUrls(codeSystemUrls)
                                .withFhirValueSetUrls(valueSetUrls)
                                .addHapiValidationEngine() // by default
                                // clearExisting is set to true so engines can be fully supplied through header
                                .withUserAgentValidationStrategy(uaValidationStrategyJson, true);
                final var session = sessionBuilder.build();
                final var bundleAsyncInteractionId = getBundleInteractionId(request);
                engine.orchestrate(session);
                session.getValidationResults().stream()
                                .map(OrchestrationEngine.ValidationResult::getIssues)
                                .filter(CollectionUtils::isNotEmpty)
                                .flatMap(List::stream)
                                .toList().stream()
                                .filter(issue -> (ResultSeverityEnum.FATAL.getCode()
                                                .equalsIgnoreCase(issue.getSeverity())))
                                .forEach(c -> {
                                        LOG.error(
                                                        "\n\n**********************FHIRController:Bundle ::  FATAL ERRORR********************** -BEGIN");
                                        LOG.error("##############################################\nFATAL ERROR Message"
                                                        + c.getMessage()
                                                        + "##############");
                                        LOG.error(
                                                        "\n\n**********************FHIRController:Bundle ::  FATAL ERRORR********************** -END");
                                });
                // TODO: if there are errors that should prevent forwarding, stop here
                // TODO: need to implement `immediate` (sync) webClient op, right now it's async
                // only
                // immediateResult is what's returned to the user while async operation
                // continues
                final var immediateResult = new HashMap<>(Map.of(
                                "resourceType", "OperationOutcome",
                                "bundleSessionId", bundleAsyncInteractionId, // for tracking in database, etc.
                                "isAsync", true,
                                "validationResults", session.getValidationResults(),
                                "rejectionsList", List.of(),
                                "rejectionsMap", Map.of(),
                                "statusUrl",
                                getBaseUrl(request) + "/Bundle/$status/" + bundleAsyncInteractionId.toString(),
                                "device", session.getDevice()));

                if (uaValidationStrategyJson != null) {
                        immediateResult.put("uaValidationStrategy",
                                        Map.of(AppConfig.Servlet.HeaderName.Request.FHIR_VALIDATION_STRATEGY,
                                                        uaValidationStrategyJson,
                                                        "issues",
                                                        sessionBuilder.getUaStrategyJsonIssues()));
                }
                if (includeRequestInOutcome) {
                        immediateResult.put("request", InteractionsFilter.getActiveRequestEnc(request));
                }
                return immediateResult;
        }

        private boolean checkForTechByDesignDisposistion(Map<String, Map<String, Object>> validationResultJson,
                        org.jooq.Configuration jooqCfg) {
                boolean hasRejections = false;
                try {
                        LOG.info("FHIRService:: Invoke Tech By Design Disposition procedure -BEGIN");
                        String validationResultStr = new com.fasterxml.jackson.databind.ObjectMapper()
                                        .writeValueAsString(validationResultJson);
                        String validationPayloadWithTechByDesignDisposistion = "";// DB function call
                        LOG.info("FHIRService:: Invoke Tech By Design Disposition procedure -END");
                        List<String> actions = JsonPath.read(validationPayloadWithTechByDesignDisposistion,
                                        "$.techByDesignDisposition[*].action");
                        hasRejections = actions.contains("reject");
                        LOG.info("TECH BY DESIGN Disposition : " + (hasRejections ? "DO NOT FORWARD TO SCORING ENGINE "
                                        : "FORWARD TO SCORING ENGINE"));
                        // registerStateDispositon
                } catch (Exception ex) {
                        LOG.error("ERROR:: ", ex);
                }
                return hasRejections;
        }

        private void sendToScoringEngine(HttpServletRequest request, String customDataLakeApi,
                        String tenantId, String payload, String provenance,
                        Map<String, Object> immediateResult, boolean includeIncomingPayloadInDB,
                        String dataLakeApiContentType, Map<String, Map<String, Object>> result) {
                final var requestURI = request.getRequestURI();

                final var DSL = udiPrimeJpaConfig.dsl();
                final var jooqCfg = DSL.configuration();
                final var bundleAsyncInteractionId = getBundleInteractionId(request);
                final var dataLakeApiBaseURL = customDataLakeApi != null && !customDataLakeApi.isEmpty()
                                ? customDataLakeApi
                                : appConfig.getDefaultDatalakeApiUrl();
                final var webClient = WebClient.builder().baseUrl(dataLakeApiBaseURL)
                                .filter(ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
                                        final var requestBuilder = new StringBuilder();
                                        requestBuilder.append(clientRequest.method().name())
                                                        .append(" ")
                                                        .append(clientRequest.url())
                                                        .append(" HTTP/1.1")
                                                        .append("\n");
                                        clientRequest.headers().forEach((name, values) -> values
                                                        .forEach(value -> requestBuilder.append(name).append(": ")
                                                                        .append(value).append("\n")));
                                        final var outboundHttpMessage = requestBuilder.toString();
                                        registerStateAccept(bundleAsyncInteractionId, requestURI,
                                                        tenantId, payload, provenance, jooqCfg);
                                        registerStateForward(provenance, outboundHttpMessage,
                                                        includeIncomingPayloadInDB, payload,
                                                        bundleAsyncInteractionId, requestURI, tenantId, immediateResult,
                                                        jooqCfg);
                                        return Mono.just(clientRequest);
                                })).build();

                try {
                        webClient.post()
                                        .uri("?processingAgent=" + tenantId)
                                        .body(BodyInserters.fromValue(payload))
                                        .header("Content-Type",
                                                        Optional.ofNullable(
                                                                        Optional.ofNullable(dataLakeApiContentType)
                                                                                        .orElse(request.getContentType()))
                                                                        .orElse(AppConfig.Servlet.FHIR_CONTENT_TYPE_HEADER_VALUE))
                                        .retrieve()
                                        .bodyToMono(String.class)
                                        .subscribe(response -> {
                                                registerStateComplete(bundleAsyncInteractionId,
                                                                requestURI, tenantId, response, provenance, jooqCfg);
                                        }, (Throwable error) -> { // Explicitly specify the type Throwable
                                                registerStateFailure(dataLakeApiBaseURL,
                                                                bundleAsyncInteractionId, error, requestURI, tenantId,
                                                                provenance, jooqCfg);
                                        });
                } catch (Exception e) {
                        immediateResult.put("exception", e.toString());
                        LOG.error("Exception while senfing to scoring engine payload with interaction id {}",
                                        getBundleInteractionId(request), e);
                }

        }

        private void registerStateAccept(String bundleAsyncInteractionId, String requestURI, String tenantId,
                        String payload,
                        String provenance, org.jooq.Configuration jooqCfg) {
                final var payloadRIHR = new RegisterInteractionHttpRequest();
                try {
                        payloadRIHR.setInteractionId(bundleAsyncInteractionId);
                        payloadRIHR.setInteractionKey(requestURI);
                        payloadRIHR.setNature(Configuration.objectMapper.valueToTree(
                                        Map.of("nature", "Original FHIR Payload", "tenant_id",
                                                        tenantId)));
                        payloadRIHR.setContentType(MimeTypeUtils.APPLICATION_JSON_VALUE);
                        try {
                                // input FHIR Bundle JSON payload
                                payloadRIHR.setPayload(
                                                Configuration.objectMapper.readTree(payload));
                        } catch (JsonProcessingException jpe) {
                                // in case the payload is not JSON store the string
                                payloadRIHR.setPayload(Configuration.objectMapper
                                                .valueToTree(payload));
                        }
                        payloadRIHR.setFromState("NONE");
                        payloadRIHR.setToState("ACCEPT_FHIR_BUNDLE");
                        payloadRIHR.setCreatedAt(OffsetDateTime.now());
                        payloadRIHR.setCreatedBy(FHIRService.class.getName());
                        payloadRIHR.setProvenance(provenance);
                        final var execResult = payloadRIHR.execute(jooqCfg);
                        LOG.info("payloadRIHR execResult" + execResult);
                } catch (Exception e) {
                        LOG.error("CALL " + payloadRIHR.getName() + " payloadRIHR error", e);
                }
        }

        private void registerStateForward(String provenance, String outboundHttpMessage,
                        boolean includeIncomingPayloadInDB,
                        String payload, String bundleAsyncInteractionId, String requestURI, String tenantId,
                        Map<String, Object> immediateResult,
                        org.jooq.Configuration jooqCfg) {
                final var forwardedAt = OffsetDateTime.now();
                final var initRIHR = new RegisterInteractionHttpRequest();
                try {
                        immediateResult.put("outboundHttpMessage",
                                        outboundHttpMessage + "\n" + (includeIncomingPayloadInDB
                                                        ? payload
                                                        : "The incoming FHIR payload was not stored (to save space).\nThis is not an error or warning just an FYI - if you'd like to see the incoming FHIR payload for debugging, next time just pass in the optional `?include-incoming-payload-in-db=true` to request payload storage for each request that you'd like to store."));
                        initRIHR.setInteractionId(bundleAsyncInteractionId);
                        initRIHR.setInteractionKey(requestURI);
                        initRIHR.setNature(Configuration.objectMapper.valueToTree(
                                        Map.of("nature", "Forward HTTP Request", "tenant_id",
                                                        tenantId)));
                        initRIHR.setContentType(MimeTypeUtils.APPLICATION_JSON_VALUE);
                        initRIHR.setPayload(Configuration.objectMapper
                                        .valueToTree(immediateResult));
                        initRIHR.setFromState("ACCEPT_FHIR_BUNDLE");
                        initRIHR.setToState("FORWARD");
                        initRIHR.setCreatedAt(forwardedAt); // don't let DB set this, use app
                                                            // time
                        initRIHR.setCreatedBy(FHIRService.class.getName());
                        initRIHR.setProvenance(provenance);
                        final var execResult = initRIHR.execute(jooqCfg);
                        LOG.info("initRIHR execResult" + execResult);
                } catch (Exception e) {
                        LOG.error("CALL " + initRIHR.getName() + " initRIHR error", e);
                }
        }

        private void registerStateComplete(String bundleAsyncInteractionId, String requestURI, String tenantId,
                        String response, String provenance, org.jooq.Configuration jooqCfg) {
                final var forwardRIHR = new RegisterInteractionHttpRequest();
                try {
                        forwardRIHR.setInteractionId(bundleAsyncInteractionId);
                        forwardRIHR.setInteractionKey(requestURI);
                        forwardRIHR.setNature(Configuration.objectMapper.valueToTree(
                                        Map.of("nature", "Forwarded HTTP Response",
                                                        "tenant_id", tenantId)));
                        forwardRIHR.setContentType(
                                        MimeTypeUtils.APPLICATION_JSON_VALUE);
                        try {
                                // expecting a JSON payload from the server
                                forwardRIHR.setPayload(Configuration.objectMapper
                                                .readTree(response));
                        } catch (JsonProcessingException jpe) {
                                // in case the payload is not JSON store the string
                                forwardRIHR.setPayload(Configuration.objectMapper
                                                .valueToTree(response));
                        }
                        forwardRIHR.setFromState("FORWARD");
                        forwardRIHR.setToState("COMPLETE");
                        forwardRIHR.setCreatedAt(OffsetDateTime.now()); // don't let DB
                                                                        // set this, use
                                                                        // app time
                        forwardRIHR.setCreatedBy(FHIRService.class.getName());
                        forwardRIHR.setProvenance(provenance);
                        final var execResult = forwardRIHR.execute(jooqCfg);
                        LOG.info("forwardRIHR execResult" + execResult);
                } catch (Exception e) {
                        LOG.error("CALL " + forwardRIHR.getName()
                                        + " forwardRIHR error", e);
                }
        }

        private void registerStateFailure(String dataLakeApiBaseURL, String bundleAsyncInteractionId, Throwable error,
                        String requestURI, String tenantId,
                        String provenance, org.jooq.Configuration jooqCfg) {
                LOG.error("Exception while sending FHIR payload to datalake URL {} for interaction id {}",
                                dataLakeApiBaseURL, bundleAsyncInteractionId, error);
                final var errorRIHR = new RegisterInteractionHttpRequest();
                try {
                        errorRIHR.setInteractionId(bundleAsyncInteractionId);
                        errorRIHR.setInteractionKey(requestURI);
                        errorRIHR.setNature(Configuration.objectMapper.valueToTree(
                                        Map.of("nature", "Forwarded HTTP Response Error",
                                                        "tenant_id", tenantId)));
                        errorRIHR.setContentType(MimeTypeUtils.APPLICATION_JSON_VALUE);
                        final var rootCauseThrowable = NestedExceptionUtils
                                        .getRootCause(error);
                        final var rootCause = rootCauseThrowable != null
                                        ? rootCauseThrowable.toString()
                                        : "null";
                        final var mostSpecificCause = NestedExceptionUtils
                                        .getMostSpecificCause(error).toString();
                        final var errorMap = new HashMap<String, Object>() {
                                {
                                        put("dataLakeApiBaseURL", dataLakeApiBaseURL);
                                        put("error", error.toString());
                                        put("message", error.getMessage());
                                        put("rootCause", rootCause);
                                        put("mostSpecificCause", mostSpecificCause);
                                        put("tenantId", tenantId);
                                }
                        };
                        if (error instanceof final WebClientResponseException webClientResponseException) {
                                String responseBody = webClientResponseException
                                                .getResponseBodyAsString();
                                errorMap.put("responseBody", responseBody);
                                String bundleId = "";
                                JsonNode rootNode = Configuration.objectMapper
                                                .readTree(responseBody);
                                JsonNode bundleIdNode = rootNode.path("bundle_id"); // Adjust
                                                                                    // this
                                                                                    // path
                                                                                    // based
                                                                                    // on
                                                                                    // actual
                                if (!bundleIdNode.isMissingNode()) {
                                        bundleId = bundleIdNode.asText();
                                }
                                LOG.error(
                                                "Exception while sending FHIR payload to datalake URL {} for interaction id {} bundle id {} response from datalake {}",
                                                dataLakeApiBaseURL,
                                                bundleAsyncInteractionId, bundleId,
                                                responseBody);
                                errorMap.put("statusCode", webClientResponseException
                                                .getStatusCode().value());
                                final var responseHeaders = webClientResponseException
                                                .getHeaders()
                                                .entrySet()
                                                .stream()
                                                .collect(Collectors.toMap(
                                                                Map.Entry::getKey,
                                                                entry -> String.join(
                                                                                ",",
                                                                                entry.getValue())));
                                errorMap.put("headers", responseHeaders);
                                errorMap.put("statusText", webClientResponseException
                                                .getStatusText());
                        }
                        errorRIHR.setPayload(Configuration.objectMapper
                                        .valueToTree(errorMap));
                        errorRIHR.setFromState("FORWARD");
                        errorRIHR.setToState("FAIL");
                        errorRIHR.setCreatedAt(OffsetDateTime.now()); // don't let DB set this, use app time
                        errorRIHR.setCreatedBy(FHIRService.class.getName());
                        errorRIHR.setProvenance(provenance);
                        final var execResult = errorRIHR.execute(jooqCfg);
                        LOG.info("forwardRIHR execResult" + execResult);
                } catch (Exception e) {
                        LOG.error("CALL " + errorRIHR.getName() + " errorRIHR error", e);
                }
        }

        private String getBundleInteractionId(HttpServletRequest request) {
                return InteractionsFilter.getActiveRequestEnc(request).requestId()
                                .toString();
        }

        private String getBaseUrl(HttpServletRequest request) {
                return Helpers.getBaseUrl(request);
        }

}
