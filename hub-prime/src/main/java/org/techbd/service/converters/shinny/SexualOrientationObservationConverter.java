package org.techbd.service.converters.shinny;

import java.util.List;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.techbd.model.csv.DemographicData;
import org.techbd.model.csv.QeAdminData;
import org.techbd.model.csv.ScreeningObservationData;
import org.techbd.model.csv.ScreeningProfileData;
import org.techbd.util.DateUtil;

@Component
public class SexualOrientationObservationConverter extends BaseConverter {

    private static final Logger LOG = LoggerFactory.getLogger(SexualOrientationObservationConverter.class.getName());

    @Override
    public ResourceType getResourceType() {
        return ResourceType.Observation;
    }

    public CanonicalType getProfileUrl() {
        return new CanonicalType(PROFILE_MAP.get("observationSexualOrientation"));
    }

    @Override
    public BundleEntryComponent  convert(Bundle bundle,DemographicData demographicData,QeAdminData qeAdminData ,
    ScreeningProfileData screeningProfileData ,List<ScreeningObservationData> screeningObservationData,String interactionId) {
        LOG.info("SexualOrientationObservationConverter:: convert BEGIN for interaction id :{} ", interactionId);
        Observation observation = new Observation();
        setMeta(observation);
        Meta meta = observation.getMeta();
        meta.setLastUpdated(DateUtil.parseDate(demographicData.getPatientLastUpdated()));
        // observation.setStatus(Observation.ObservationStatus.fromCode(demographicData.getSexualOrientationStatus()));
        // CodeableConcept code = new CodeableConcept();
        // code.addCoding(new Coding(demographicData.getSexualOrientationCodeSystemName(),
        //         demographicData.getSexualOrientationCodeCode(), demographicData.getSexualOrientationCodeDisplay()));
        // observation.setCode(code);
        // CodeableConcept value = new CodeableConcept();
        // value.addCoding(new Coding(demographicData.getSexualOrientationValueCodeSystemName(),
        //         demographicData.getSexualOrientationValueCode(),
        //         demographicData.getSexualOrientationValueCodeDescription()));
        // observation.setValue(value);
        // observation.setEffective(new DateTimeType(demographicData.getSexualOrientationLastUpdated()));
        // Narrative text = new Narrative();
        // text.setStatus(Narrative.NarrativeStatus.fromCode(demographicData.getPatientTextStatus()));
        // observation.setText(text);
        BundleEntryComponent entry = new BundleEntryComponent();
        entry.setResource(observation);
        LOG.info("SexualOrientationObservationConverter:: convert END for interaction id :{} ", interactionId);
        return entry;
    }

}
