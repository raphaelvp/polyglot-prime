package org.techbd.service.csv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.assertj.core.api.SoftAssertions;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Encounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.techbd.model.csv.DemographicData;
import org.techbd.model.csv.QeAdminData;
import org.techbd.model.csv.ScreeningObservationData;
import org.techbd.model.csv.ScreeningProfileData;
import org.techbd.service.converters.shinny.EncounterConverter;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;

@ExtendWith(MockitoExtension.class)
class EncounterConverterTest {
    private static final Logger LOG = LoggerFactory.getLogger(EncounterConverterTest.class.getName());

    @InjectMocks
    private EncounterConverter encounterConverter;

    @Test
    void testConvert() throws Exception {
        // Create necessary data objects for the test
        final Bundle bundle = new Bundle();  // Ensure this is populated with relevant data
        final DemographicData demographicData = CsvTestHelper.createDemographicData();  // Create DemographicData instance
        final List<ScreeningObservationData> screeningDataList = CsvTestHelper.createScreeningObservationData();  // Create list of ScreeningData
        final QeAdminData qrAdminData = CsvTestHelper.createQeAdminData();  // Create QeAdminData instance
        final ScreeningProfileData screeningResourceData = CsvTestHelper.createScreeningProfileData();  // Create ScreeningResourceData
    
        // Instantiate the EncounterConverter
        EncounterConverter encounterConverter = new EncounterConverter();
    
        // Call the convert method of the encounter converter
        final BundleEntryComponent result = encounterConverter.convert(bundle, demographicData, qrAdminData, screeningResourceData,
                 screeningDataList, "interactionId").get(0);;
    
        // Create soft assertions to verify the result
        final SoftAssertions softly = new SoftAssertions();
    
        // Assert that the result is not null
        softly.assertThat(result).isNotNull();
    
        // Assert that the result resource is an instance of Encounter
        softly.assertThat(result.getResource()).isInstanceOf(Encounter.class);
    
        // Cast the result to Encounter and assert various properties
        final Encounter encounter = (Encounter) result.getResource();
    
        // Assert that the encounter ID is not null or empty and matches expected
        softly.assertThat(encounter.getId()).isNotEmpty();
        softly.assertThat(encounter.getId()).isEqualTo("Encounter-interactionId");
    
        // Assert that the encounter status is active (FINISHED)
       //softly.assertThat(encounter.getStatus()).isEqualTo("FINISHED");
    
        // Assert that the encounter has the correct encounter type
        softly.assertThat(encounter.getType()).hasSize(1);
        softly.assertThat(encounter.getTypeFirstRep().getCodingFirstRep().getCode()).isEqualTo("405672008");
    
        // Assert all soft assertions
        softly.assertAll();
    }
    

    @Test
    void testGeneratedJson() throws Exception {
        final var bundle = new Bundle();
        final var demographicData = CsvTestHelper.createDemographicData();
        final var screeningDataList = CsvTestHelper.createScreeningObservationData();
        final var qrAdminData = CsvTestHelper.createQeAdminData();
        final ScreeningProfileData screeningResourceData = CsvTestHelper.createScreeningProfileData();

        final var result = encounterConverter.convert(bundle, demographicData, qrAdminData, screeningResourceData,
                screeningDataList,
                "interactionId");

        final Encounter encounter = (Encounter) result.get(0).getResource();
        final var filePath = "src/test/resources/org/techbd/csv/generated-json/encounter.json";
        final FhirContext fhirContext = FhirContext.forR4();
        final IParser fhirJsonParser = fhirContext.newJsonParser().setPrettyPrint(true);
        final String fhirResourceJson = fhirJsonParser.encodeResourceToString(encounter);
        final Path outputPath = Paths.get(filePath);
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, fhirResourceJson);
    }
}
