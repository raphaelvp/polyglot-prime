package org.techbd.service.converters;

import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.ResourceType;
import org.springframework.stereotype.Component;
import org.techbd.service.constants.ShinnyProfileConstants;

@Component
public class EncounterConverter implements IConverter {

    @Override
    public CanonicalType getProfileUrl() {
        return new CanonicalType(ShinnyProfileConstants.PROFILE_SHINNY_ENCOUNTER);
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.Encounter;
    }

}
