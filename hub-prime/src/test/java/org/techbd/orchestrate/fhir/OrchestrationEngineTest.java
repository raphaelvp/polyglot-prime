package org.techbd.orchestrate.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.OperationOutcomeIssueComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.context.FhirContext;

class OrchestrationEngineTest {

    private OrchestrationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new OrchestrationEngine();
    }

    @Test
    void testOrchestrateSingleSession() {
        OrchestrationEngine.OrchestrationSession session = engine.session()
                .withPayloads(List.of("not a valid payload"))
                .withFhirProfileUrl("http://example.com/fhirProfile")
                .addHapiValidationEngine()
                .build();

        engine.orchestrate(session);

        assertThat(engine.getSessions()).hasSize(1);
        assertThat(engine.getSessions().get(0).getPayloads()).containsExactly("not a valid payload");
        assertThat(engine.getSessions().get(0).getFhirProfileUrl()).isEqualTo("http://example.com/fhirProfile");

        List<OrchestrationEngine.ValidationResult> results = engine.getSessions().get(0).getValidationResults();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).isValid()).isFalse();
        OperationOutcome operationOutcome = (OperationOutcome) FhirContext.forR4().newJsonParser().parseResource(results.get(0).getOperationOutcome());
        List<OperationOutcomeIssueComponent> issues = operationOutcome.getIssue();
        assertThat(issues).extracting("diagnostics").containsExactly(
                "HAPI-1861: Failed to parse JSON encoded FHIR content: HAPI-1859: Content does not appear to be FHIR JSON, first non-whitespace character was: 'n' (must be '{')");
    }

    @Test
    void testOrchestrateMultipleSessions() {
        OrchestrationEngine.OrchestrationSession session1 = engine.session()
                .withPayloads(List.of("payload1"))
                .withFhirProfileUrl("http://example.com/fhirProfile")
                .addHapiValidationEngine()
                .build();

        OrchestrationEngine.OrchestrationSession session2 = engine.session()
                .withPayloads(List.of("payload2"))
                .withFhirProfileUrl("http://example.com/fhirProfile")
                .addHl7ValidationApiEngine()
                .build();

        engine.orchestrate(session1, session2);

        assertThat(engine.getSessions()).hasSize(2);

        OrchestrationEngine.OrchestrationSession retrievedSession1 = engine.getSessions().get(0);
        assertThat(retrievedSession1.getPayloads()).containsExactly("payload1");
        assertThat(retrievedSession1.getFhirProfileUrl()).isEqualTo("http://example.com/fhirProfile");
        assertThat(retrievedSession1.getValidationResults()).hasSize(1);
        assertThat(retrievedSession1.getValidationResults().get(0).isValid()).isFalse();
        OperationOutcome operationOutcome = (OperationOutcome) FhirContext.forR4().newJsonParser().parseResource(retrievedSession1.getValidationResults().get(0).getOperationOutcome());
        List<OperationOutcomeIssueComponent> issues = operationOutcome.getIssue();
        assertThat(issues).extracting("diagnostics")
                .containsExactly(
                        "HAPI-1861: Failed to parse JSON encoded FHIR content: HAPI-1859: Content does not appear to be FHIR JSON, first non-whitespace character was: 'p' (must be '{')");

        OrchestrationEngine.OrchestrationSession retrievedSession2 = engine.getSessions().get(1);
        assertThat(retrievedSession2.getPayloads()).containsExactly("payload2");
        assertThat(retrievedSession2.getFhirProfileUrl()).isEqualTo("http://example.com/fhirProfile");
        assertThat(retrievedSession2.getValidationResults()).hasSize(1);
        assertThat(retrievedSession2.getValidationResults().get(0).isValid()).isFalse();
    }

    @Test
    void testValidationEngineCaching() {
        OrchestrationEngine.OrchestrationSession session1 = engine.session()
                .withPayloads(List.of("payload1"))
                .withFhirProfileUrl("http://example.com/fhirProfile")
                .addHapiValidationEngine()
                .build();

        OrchestrationEngine.OrchestrationSession session2 = engine.session()
                .withPayloads(List.of("payload2"))
                .withFhirProfileUrl("http://example.com/fhirProfile")
                .addHapiValidationEngine()
                .build();

        engine.orchestrate(session1, session2);
        Map<String, Map<String, String>> igPackages = new HashMap<>();
        String igVersion = new String();
        Map<String, String> codeSystemMap = new HashMap<>();
        codeSystemMap.put("shinnyConsentProvisionTypesVS", "http://example.com/shinnyConsentProvision");
        assertThat(engine.getSessions()).hasSize(2);
        assertThat(engine.getValidationEngine(OrchestrationEngine.ValidationEngineIdentifier.HAPI,
                "http://example.com/fhirProfile", igPackages, igVersion))
                .isSameAs(engine.getValidationEngine(
                        OrchestrationEngine.ValidationEngineIdentifier.HAPI,
                        "http://example.com/fhirProfile", igPackages, igVersion));
    }
}
