package org.techbd.service.converters.shinny;

import java.util.List;
import java.util.Map;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Narrative.NarrativeStatus;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.techbd.model.csv.DemographicData;
import org.techbd.model.csv.QeAdminData;
import org.techbd.model.csv.ScreeningObservationData;
import org.techbd.model.csv.ScreeningProfileData;
import org.techbd.util.CsvConstants;
import org.techbd.util.CsvConversionUtil;
import org.techbd.util.DateUtil;

import ca.uhn.fhir.context.FhirContext;

/**
 * Converts data into a FHIR Encounter resource.
 */
@Component
@Order(5)
public class EncounterConverter extends BaseConverter {
    private static final Logger LOG = LoggerFactory.getLogger(EncounterConverter.class.getName());

    /**
     * Returns the resource type associated with this converter.
     *
     * @return The FHIR ResourceType.Encounter enum.
     */
    @Override
    public ResourceType getResourceType() {
        return ResourceType.Encounter;
    }

    /**
     * Converts encounter-related data into a FHIR Encounter resource wrapped in a
     * BundleEntryComponent.
     *
     * @param bundle                The FHIR Bundle to which the encounter data is
     *                              related.
     * @param demographicData       The demographic data related to the patient.
     * @param screeningDataList     The list of screening data (if required for the
     *                              encounter context).
     * @param qrAdminData           The administrative data related to the patient
     *                              or organization.
     * @param screeningResourceData Additional screening resource data (if needed).
     * @param interactionId         The interaction ID used for tracking or
     *                              referencing the conversion.
     * @return A BundleEntryComponent containing the converted FHIR Encounter
     *         resource.
     */
    @Override
    public List<BundleEntryComponent> convert(Bundle bundle, DemographicData demographicData, QeAdminData qeAdminData,
            ScreeningProfileData screeningProfileData, List<ScreeningObservationData> screeningObservationData,
            String interactionId, Map<String, String> idsGenerated) {

        Encounter encounter = new Encounter();
        setMeta(encounter);

        encounter.setId(CsvConversionUtil.sha256(screeningProfileData.getEncounterId()));

        String fullUrl = "http://shinny.org/us/ny/hrsn/Encounter/" + encounter.getId();

        Meta meta = encounter.getMeta();
        meta.setLastUpdated(DateUtil.parseDate(qeAdminData.getFacilityLastUpdated()));

        populateEncounterStatus(encounter, screeningProfileData);

        populateEncounterClass(encounter, screeningProfileData);

        populateEncounterType(encounter, screeningProfileData);

        populateEncounterPeriod(encounter, screeningProfileData);

        populatePatientReference(encounter, idsGenerated);

        populateLocationReference(encounter, screeningProfileData, idsGenerated);
        Narrative text = new Narrative();
        text.setStatus(NarrativeStatus.GENERATED);
        encounter.setText(text);
        BundleEntryComponent bundleEntryComponent = new BundleEntryComponent();
        bundleEntryComponent.setFullUrl(fullUrl);
        bundleEntryComponent.setRequest(new Bundle.BundleEntryRequestComponent().setMethod(HTTPVerb.POST)
                .setUrl("http://shinny.org/us/ny/hrsn/Encounter/" + encounter.getId()));
        bundleEntryComponent.setResource(encounter);

        return List.of(bundleEntryComponent);
    }

    private void populatePatientReference(Encounter encounter, Map<String, String> idsGenerated) {
        encounter.setSubject(new Reference("Patient/" + idsGenerated.get(CsvConstants.PATIENT_ID)));
    }

    private static void populateEncounterClass(Encounter encounter, ScreeningProfileData data) {
        if (data.getEncounterClassCode() != null) {
            Coding encounterClass = new Coding();
            encounterClass.setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode");
            encounterClass.setCode(data.getEncounterClassCode());
            encounter.setClass_(encounterClass);
        }
    }

    private static void populateEncounterType(Encounter encounter, ScreeningProfileData data) {
        if (data.getEncounterTypeCode() != null || data.getEncounterTypeCodeDescription() != null) {
            CodeableConcept encounterType = new CodeableConcept();

            if (data.getEncounterTypeCode() != null) {
                Coding coding = new Coding();
                coding.setCode(data.getEncounterTypeCode());
                coding.setSystem(data.getEncounterTypeCodeSystem());
                coding.setDisplay(data.getEncounterTypeCodeDescription());
                encounterType.addCoding(coding);
            }

            if (data.getEncounterTypeCodeDescription() != null) {
                encounterType.setText(data.getEncounterTypeCodeDescription());
            }

            encounter.getType().add(encounterType);
        }
    }

    private void populateEncounterStatus(Encounter encounter, ScreeningProfileData screeningResourceData) {
        if (screeningResourceData != null && screeningResourceData.getEncounterStatusCode() != null) {
            encounter.setStatus(Encounter.EncounterStatus.fromCode(screeningResourceData.getEncounterStatusCode()));
        } else {
            encounter.setStatus(Encounter.EncounterStatus.UNKNOWN);
        }
    }

    private void populateEncounterPeriod(Encounter encounter, ScreeningProfileData screeningResourceData) {
        if (screeningResourceData != null) {
            String startDateTime = "2024-02-23T00:00:00Z"; // TODO : remove static reference
            String endDateTime = "2024-02-23T01:00:00Z"; // TODO : remove static reference

            if (startDateTime != null) {
                encounter.getPeriod().setStart(DateUtil.convertStringToDate(startDateTime));
            }

            if (endDateTime != null) {
                encounter.getPeriod().setEnd(DateUtil.convertStringToDate(endDateTime));
            }
        }
    }

    private void populateLocationReference(Encounter encounter, ScreeningProfileData screeningResourceData,
            Map<String, String> idsGenerated) {
        if (screeningResourceData != null) {
            encounter.addLocation(new Encounter.EncounterLocationComponent()
                    .setLocation(new Reference("Location/" + idsGenerated.get(CsvConstants.PATIENT_ID))));
        }
    }
}
