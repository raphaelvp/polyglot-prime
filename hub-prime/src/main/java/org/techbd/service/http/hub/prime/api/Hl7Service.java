package org.techbd.service.http.hub.prime.api;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.techbd.conf.Configuration;
import org.techbd.service.converters.BundleToFHIRConverter;
import org.techbd.service.http.GitHubUserAuthorizationFilter;
import org.techbd.service.http.InteractionsFilter;
import org.techbd.udi.UdiPrimeJpaConfig;
import org.techbd.udi.auto.jooq.ingress.routines.RegisterInteractionHttpRequestAll;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import io.github.linuxforhealth.hl7.HL7ToFHIRConverter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Service
public class Hl7Service {
    private static final Logger LOG = LoggerFactory.getLogger(Hl7Service.class.getName());
    private final BundleToFHIRConverter bundleToFHIRConverter;
    private final FHIRService fhirService;
    private final UdiPrimeJpaConfig udiPrimeJpaConfig;

    @Value("${org.techbd.service.http.interactions.default-persist-strategy:#{null}}")
    private String defaultPersistStrategy;

    @Value("${org.techbd.service.http.interactions.saveUserDataToInteractions:true}")
    private boolean saveUserDataToInteractions;

    public Hl7Service(final FHIRService fhirService, final UdiPrimeJpaConfig udiPrimeJpaConfig,
            final BundleToFHIRConverter bundleToFHIRConverter) {
        this.fhirService = fhirService;
        this.udiPrimeJpaConfig = udiPrimeJpaConfig;
        this.bundleToFHIRConverter = bundleToFHIRConverter;
    }

    public Object processHl7Message(String hl7Payload, String tenantId, String healthCheck, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        final var interactionId = getBundleInteractionId(request);
        final var dslContext = udiPrimeJpaConfig.dsl();
        final var jooqCfg = dslContext.configuration();
        try {
            LOG.info("HL7Service::processHl7Message BEGIN for interactionid : {} tenantId :{} ", interactionId,
                    tenantId);
            registerStateHl7Accept(jooqCfg, hl7Payload, tenantId, interactionId, healthCheck, request, response);
            final var bundleJson = convertToFHIRJson(jooqCfg, hl7Payload, tenantId, interactionId);
            if (null != bundleJson) {
                final String shinnyFhirJson = convertToShinnyFHIRJson(jooqCfg, bundleJson, tenantId, interactionId);
                if (null != shinnyFhirJson) {
                    registerStateHl7Parse(jooqCfg, shinnyFhirJson, tenantId, interactionId, healthCheck, request,
                            response);
                    LOG.info(
                            "HL7Service::processHl7Message END -start processing FHIR Json for interactionid : {} tenantId :{} ",
                            interactionId, tenantId);
                    return fhirService.processBundle(shinnyFhirJson, tenantId, null, null, null, null, null,
                            healthCheck, false,
                            false,
                            false, request, response, null, true);
                }
            }
        } catch (Exception ex) {
            LOG.error(" ERROR:: HL7Service::processHl7Message BEGIN for interactionid : {} tenantId :{} ",
                    interactionId, tenantId, ex);
            registerStateFailed(jooqCfg, interactionId,
                    request.getRequestURI(), tenantId, ex.getMessage(),
                    "%s.processHl7Message".formatted(Hl7Service.class.getName()));
        }
        return null;
    }

    public String convertToShinnyFHIRJson(org.jooq.Configuration jooqCfg, String bundleJson, String tenantId,
            String interactionId) {
        LOG.info("HL7Service::convertToShinnyFHIRJson BRGIN for interactionid : {} tenantId :{} ", interactionId,
                tenantId);
        try {
            return bundleToFHIRConverter.convertToShinnyFHIRJson(bundleJson);
        } catch (Exception ex) {
            LOG.error(
                    "ERROR:: HL7Service::convertToShinnyFHIRJson Exception during conversion of JSON to Shinny Bundle Json   for interactionid : {} tenantId :{} ",
                    interactionId,
                    tenantId, ex);
            // TODO - call main proc
            registerStateFailed(jooqCfg, interactionId, null, tenantId,
                    "Exception during conversion of JSON to Shinny Bundle Json "
                            + ex.getMessage(),
                    "%s.convertToShinnyFHIRJson".formatted(Hl7Service.class.getName()));
        }
        LOG.info("HL7Service::convertToShinnyFHIRJson END for interactionid : {} tenantId :{} ", interactionId,
                tenantId);
        return null;
    }

    private String getBundleInteractionId(HttpServletRequest request) {
        return InteractionsFilter.getActiveRequestEnc(request).requestId()
                .toString();
    }

    private void registerStateHl7Parse(org.jooq.@NotNull Configuration jooqCfg, String shinnyFhirJson, String tenantId,
            String interactionId,
            String healthCheck, HttpServletRequest request, HttpServletResponse response) {
        LOG.info("REGISTER State HL7 Parse : BEGIN for interaction id  : {} tenant id : {}",
                interactionId, tenantId);
        final var forwardedAt = OffsetDateTime.now();
        final var initRIHR = new RegisterInteractionHttpRequestAll();
        try {
            initRIHR.setInteractionId(interactionId);
            initRIHR.setInteractionKey(request.getRequestURI());
            initRIHR.setNature((JsonNode) Configuration.objectMapper.valueToTree(
                    Map.of("nature", "Converted FHIR JSON", "tenant_id",
                            tenantId)));
            initRIHR.setContentType(MimeTypeUtils.APPLICATION_JSON_VALUE);
            initRIHR.setPayload((JsonNode) Configuration.objectMapper
                    .valueToTree(shinnyFhirJson));
            initRIHR.setFromState("HL7_ACCEPT");
            initRIHR.setToState("HL7_PARSE");
            initRIHR.setCreatedAt(forwardedAt); // don't let DB set this, use app
            // time
            initRIHR.setCreatedBy(FHIRService.class.getName());
            initRIHR.setProvenance("%s.registerStateHl7Parse".formatted(Hl7Service.class.getName()));
            if (saveUserDataToInteractions) {
                setUserDetails(initRIHR, request);
            } else {
                LOG.info("User details are not saved with Interaction as saveUserDataToInteractions: "
                        + saveUserDataToInteractions);
            }
            final var execResult = initRIHR.execute(jooqCfg);
            LOG.info("REGISTER State HL7 Parse : END for interaction id  : {} tenant id : {}",
                    interactionId, tenantId);
        } catch (Exception e) {
            LOG.error("ERROR:: REGISTER State HL7 Parse CALL for interaction id : {} tenant id : {}"
                    + initRIHR.getName() + " initRIHR error", interactionId, tenantId,
                    e);
        }
    }

    private void registerStateFailed(org.jooq.Configuration jooqCfg, String interactionId,
            String requestURI, String tenantId,
            String response, String provenance) {
        LOG.info("REGISTER State Fail : BEGIN for interaction id :  {} tenant id : {}",
                interactionId, tenantId);
        final var forwardRIHR = new RegisterInteractionHttpRequestAll();
        try {
            forwardRIHR.setInteractionId(interactionId);
            forwardRIHR.setInteractionKey(requestURI);
            forwardRIHR.setNature((JsonNode) Configuration.objectMapper.valueToTree(
                    Map.of("nature", "HL7 Handling Failed",
                            "tenant_id", tenantId)));
            forwardRIHR.setContentType(
                    MimeTypeUtils.APPLICATION_JSON_VALUE);
            try {
                // expecting a JSON payload from the server
                forwardRIHR.setPayload(Configuration.objectMapper
                        .readTree(response));
            } catch (JsonProcessingException jpe) {
                // in case the payload is not JSON store the string
                forwardRIHR.setPayload((JsonNode) Configuration.objectMapper
                        .valueToTree(response));
            }
            forwardRIHR.setFromState("HL7_ACCEPT");
            forwardRIHR.setToState("FAIL");
            forwardRIHR.setCreatedAt(OffsetDateTime.now()); // don't let DB
            // set this, use
            // app time
            forwardRIHR.setCreatedBy(FHIRService.class.getName());
            forwardRIHR.setProvenance(provenance);
            final var execResult = forwardRIHR.execute(jooqCfg);     
        } catch (Exception e) {
            LOG.error("ERROR:: REGISTER State Fail CALL for interaction id : {} tenant id : {} "
                    + forwardRIHR.getName()
                    + " forwardRIHR error", interactionId, tenantId, e);
        }
        LOG.info("REGISTER State Fail : END for interaction id : {} tenant id : {}" ,
        interactionId, tenantId);
    }

    private void registerStateHl7Accept(org.jooq.Configuration jooqCfg, String hl7Payload, String tenantId,
            String interactionId,
            String healthCheck, HttpServletRequest request, HttpServletResponse response) throws IOException {
        LOG.info("REGISTER State HL7 ACCEPT : BEGIN for interaction id :  {} tenant id : {}",
                interactionId, tenantId);
        final var forwardedAt = OffsetDateTime.now();
        final var initRIHR = new RegisterInteractionHttpRequestAll();
        try {
            initRIHR.setInteractionId(interactionId);
            initRIHR.setInteractionKey(request.getRequestURI());
            initRIHR.setNature((JsonNode) Configuration.objectMapper.valueToTree(
                    Map.of("nature", "Original HL7 Payload", "tenant_id",
                            tenantId)));
            initRIHR.setContentType(MimeTypeUtils.APPLICATION_JSON_VALUE);
            initRIHR.setPayloadText(hl7Payload);
            initRIHR.setFromState("NONE");
            initRIHR.setToState("HL7_ACCEPT");
            initRIHR.setCreatedAt(forwardedAt); // don't let DB set this, use app
            initRIHR.setCreatedBy(FHIRService.class.getName());
            initRIHR.setProvenance("%s.registerStateHl7Parse".formatted(Hl7Service.class.getName()));
            if (saveUserDataToInteractions) {
                setUserDetails(initRIHR, request);
            } else {
                LOG.info("User details are not saved with Interaction as saveUserDataToInteractions: "
                        + saveUserDataToInteractions);
            }
            final var execResult = initRIHR.execute(jooqCfg);
        } catch (Exception e) {
            LOG.error("ERROR:: REGISTER State HL7 Parse CALL for interaction id : {} tenant id : {}"
                    + initRIHR.getName() + " initRIHR error", interactionId, tenantId,
                    e);
        }
        LOG.info("REGISTER State HL7 ACCEPT : END for interaction id : {} tenant id : {}" ,
                interactionId, tenantId);
    }

    private void setUserDetails(RegisterInteractionHttpRequestAll rihr, HttpServletRequest request) {
        var curUserName = "API_USER";
        var gitHubLoginId = "N/A";
        final var sessionId = request.getRequestedSessionId();
        var userRole = "API_ROLE";
        final var curUser = GitHubUserAuthorizationFilter.getAuthenticatedUser(request);
        if (curUser.isPresent()) {
            final var ghUser = curUser.get().ghUser();
            if (ghUser != null) {
                curUserName = Optional.ofNullable(ghUser.name()).orElse("NO_DATA");
                gitHubLoginId = Optional.ofNullable(ghUser.gitHubId()).orElse("NO_DATA");
                userRole = curUser.get().principal().getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.joining(","));
                LOG.info("userRole: " + userRole);
                userRole = "DEFAULT_ROLE"; // TODO -set user role
            }
        }
        rihr.setUserName(curUserName);
        rihr.setUserId(gitHubLoginId);
        rihr.setUserSession(sessionId);
        rihr.setUserRole(userRole);
    }

    public String convertToFHIRJson(org.jooq.Configuration jooqCfg, String hl7Payload, String tenantId,
            String interactionId) {
        LOG.info("HL7Service::convertToFHIRJson BEGIN for interactionid : {} tenantId :{} ", interactionId,
                tenantId);
        try {
            HL7ToFHIRConverter ftv = new HL7ToFHIRConverter();
            final var fhirJson = ftv.convert(hl7Payload);
            return fhirJson;
        } catch (Exception ex) {
            LOG.error(
                    "ERROR:: HL7Service::convertToFHIRJson Exception during the initial conversion of HL7 to JSON using Linuxforhelath HL7ToFHIRConverter  for interactionid : {} tenantId :{} ",
                    interactionId,
                    tenantId, ex);
            // TODO - call main proc
            registerStateFailed(jooqCfg, interactionId, null, tenantId,
                    "Exception during the initial conversion of HL7 to JSON using Linuxforhelath HL7ToFHIRConverter "
                            + ex.getMessage(),
                    "%s.convertToFHIRJson".formatted(Hl7Service.class.getName()));
        }
        LOG.info("HL7Service::convertToFHIRJson END for interactionid : {} tenantId :{} ", interactionId,
                tenantId);
        return null;
    }
}
