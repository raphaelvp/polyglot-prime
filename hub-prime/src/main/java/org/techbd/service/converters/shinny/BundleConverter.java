package org.techbd.service.converters.shinny;

import java.util.List;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.techbd.model.csv.DemographicData;
import org.techbd.util.CsvConversionUtil;
import org.techbd.util.DateUtil;

@Component
public class BundleConverter {

    private static final Logger LOG = LoggerFactory.getLogger(BundleConverter.class.getName());
    public ResourceType getResourceType() {
        return ResourceType.Bundle;
    }

    /**
     * Generates an empty FHIR Bundle with a single empty entry and a default Meta
     * section.
     *
     * @return a Bundle with type set to COLLECTION, one empty entry, and Meta
     *         information.
     */
    public Bundle generateEmptyBundle(String interactionId,String igVersion,DemographicData demographicData) {
        Bundle bundle = new Bundle();
        bundle.setId("AHCHRSNScreeningResponse-"+CsvConversionUtil.sha256(demographicData.getPatientMrIdValue()));
        bundle.setType(Bundle.BundleType.TRANSACTION);
        Meta meta = new Meta();
        meta.setLastUpdated(DateUtil.parseDate(demographicData.getPatientLastUpdated()));
        meta.setVersionId(igVersion);
        meta.setProfile(List.of(new CanonicalType("http://shinny.org/us/ny/hrsn/StructureDefinition/SHINNYBundleProfile")));
        bundle.setMeta(meta);
        LOG.info("Empty FHIR Bundle template generated with Meta and one empty entry for interactionId : {}.",
                interactionId);
        return bundle;
    }
}
