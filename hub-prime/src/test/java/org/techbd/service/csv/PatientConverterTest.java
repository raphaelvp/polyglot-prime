package org.techbd.service.csv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.assertj.core.api.SoftAssertions;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.techbd.model.csv.DemographicData;
import org.techbd.model.csv.QeAdminData;
import org.techbd.model.csv.ScreeningData;
import org.techbd.service.CodeSystemLookupService;
import org.techbd.service.converters.shinny.PatientConverter;
import org.techbd.util.CsvConversionUtil;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;

@ExtendWith(MockitoExtension.class)
class PatientConverterTest {

    @Mock
    private CodeSystemLookupService codeSystemLookupService;

    @InjectMocks
    private PatientConverter patientConverter;

    @BeforeEach
    void setUp() {
        patientConverter = new PatientConverter(codeSystemLookupService);
    }

    @Test
    void testConvert() throws Exception {
        Bundle bundle = new Bundle();
        DemographicData demographicData = createDemographicData();
        List<ScreeningData> screeningDataList = createScreeningData();
        QeAdminData qrAdminData = createQeAdminData();

        BundleEntryComponent result = patientConverter.convert(bundle, demographicData, screeningDataList, qrAdminData,
                "interactionId");
        SoftAssertions softly = new SoftAssertions();

        softly.assertThat(result).isNotNull();
        softly.assertThat(result.getResource()).isInstanceOf(Patient.class);

        Patient patient = (Patient) result.getResource();
        softly.assertThat(patient.getId()).isNotEmpty();
        softly.assertThat(patient.getId())
                .isNotNull()
                .isEqualTo("EncounterExampleCUMC-11223344");
        softly.assertThat(patient.getLanguage()).isEqualTo("en");
        softly.assertThat(patient.hasName()).isTrue();
        softly.assertThat(patient.getNameFirstRep().getGivenAsSingleString()).isEqualTo("Jon Bob");
        softly.assertThat(patient.getNameFirstRep().getFamily()).isEqualTo("Doe");
        softly.assertThat(patient.hasGender()).isTrue();
        softly.assertThat(patient.getGender().toCode()).isEqualTo("male");
        softly.assertThat(patient.getBirthDate()).isNotNull();
        softly.assertThat(patient.getAddressFirstRep().getCity()).isEqualTo("New York");
        Identifier mrIdentifier = patient.getIdentifier().stream()
                .filter(identifier -> "MR".equals(identifier.getType().getCodingFirstRep().getCode()))
                .findFirst()
                .orElse(null);
        softly.assertThat(mrIdentifier).isNotNull();
        softly.assertThat(mrIdentifier.getSystem()).isEqualTo("http://www.scn.gov/facility/CUMC");
        softly.assertThat(mrIdentifier.getValue()).isEqualTo("11223344");

        Identifier maIdentifier = patient.getIdentifier().stream()
                .filter(identifier -> "MA".equals(identifier.getType().getCodingFirstRep().getCode()))
                .findFirst()
                .orElse(null);
        softly.assertThat(maIdentifier).isNotNull();
        softly.assertThat(maIdentifier.getSystem()).isEqualTo("http://www.medicaid.gov");
        softly.assertThat(maIdentifier.getValue()).isEqualTo("AA12345C");

        Identifier ssnIdentifier = patient.getIdentifier().stream()
                .filter(identifier -> "SSN".equals(identifier.getType().getCodingFirstRep().getCode()))
                .findFirst()
                .orElse(null);
        softly.assertThat(ssnIdentifier).isNotNull();
        softly.assertThat(ssnIdentifier.getSystem()).isEqualTo("http://www.ssa.gov");
        softly.assertThat(ssnIdentifier.getValue()).isEqualTo("999-34-2964");

        softly.assertThat(patient.getExtension()).hasSize(3);

        Extension sexAtBirthExtension = patient.getExtensionByUrl("expected-sex-at-birth-url");
        softly.assertThat(sexAtBirthExtension).isNotNull();
        softly.assertThat(sexAtBirthExtension.getValue().primitiveValue()).isEqualTo("M");

        Extension ethnicityExtension = patient.getExtensionByUrl("expected-ethnicity-url");
        softly.assertThat(ethnicityExtension).isNotNull();
        softly.assertThat(ethnicityExtension.getValue().primitiveValue()).isEqualTo("Hispanic or Latino");

        Extension raceExtension = patient.getExtensionByUrl("expected-race-url");
        softly.assertThat(raceExtension).isNotNull();
        softly.assertThat(raceExtension.getValue().primitiveValue()).isEqualTo("Asian");
        softly.assertAll();
    }

    @Test
    @Disabled
    void testGeneratedJson() throws Exception {
        var bundle = new Bundle();
        var demographicData = createDemographicData();
        var screeningDataList = createScreeningData();
        var qrAdminData = createQeAdminData();
        var result = patientConverter.convert(bundle, demographicData, screeningDataList, qrAdminData, "interactionId");
        Patient patient = (Patient) result.getResource();
        var filePath = "src/test/resources/org/techbd/csv/generated-json/patient.json";
        FhirContext fhirContext = FhirContext.forR4();
        IParser fhirJsonParser = fhirContext.newJsonParser().setPrettyPrint(true);
        String fhirResourceJson = fhirJsonParser.encodeResourceToString(patient);
        Path outputPath = Paths.get(filePath);
        Files.createDirectories(outputPath.getParent()); 
        Files.writeString(outputPath, fhirResourceJson);
    }
    private DemographicData createDemographicData() throws IOException {
        String csv = """
                PATIENT_MR_ID|FACILITY_ID|CONSENT_STATUS|CONSENT_TIME|GIVEN_NAME|MIDDLE_NAME|FAMILY_NAME|GENDER|SEX_AT_BIRTH_CODE|SEX_AT_BIRTH_CODE_DESCRIPTION|SEX_AT_BIRTH_CODE_SYSTEM|PATIENT_BIRTH_DATE|ADDRESS1|ADDRESS2|CITY|DISTRICT|STATE|ZIP|PHONE|SSN|PERSONAL_PRONOUNS_CODE|PERSONAL_PRONOUNS_CODE_DESCRIPTION|PERSONAL_PRONOUNS_CODE_SYSTEM_NAME|GENDER_IDENTITY_CODE|GENDER_IDENTITY_CODE_DESCRIPTION|GENDER_IDENTITY_CODE_SYSTEM_NAME|SEXUAL_ORIENTATION_CODE|SEXUAL_ORIENTATION_CODE_DESCRIPTION|SEXUAL_ORIENTATION_CODE_SYSTEM_NAME|PREFERRED_LANGUAGE_CODE|PREFERRED_LANGUAGE_CODE_DESCRIPTION|PREFERRED_LANGUAGE_CODE_SYSTEM_NAME|RACE_CODE|RACE_CODE_DESCRIPTION|RACE_CODE_SYSTEM_NAME|ETHNICITY_CODE|ETHNICITY_CODE_DESCRIPTION|ETHNICITY_CODE_SYSTEM_NAME|MEDICAID_CIN|PATIENT_LAST_UPDATED|RELATIONSHIP_PERSON_CODE|RELATIONSHIP_PERSON_DESCRIPTION|RELATIONSHIP_PERSON_SYSTEM|RELATIONSHIP_PERSON_GIVEN_NAME|RELATIONSHIP_PERSON_FAMILY_NAME|RELATIONSHIP_PERSON_TELECOM_SYSTEM|RELATIONSHIP_PERSON_TELECOM_VALUE
                11223344|CUMC|active|2024-02-23T00:00:00Z|Jon|Bob|Doe|male|M|Male|http://hl7.org/fhir/us/core/StructureDefinition/us-core-birthsex|1981-07-16|115 Broadway Apt2||New York|MANHATTAN|NY|10032|1234567890|999-34-2964|LA29518-0|he/him/his/his/himself|http://loinc.org|LA22878-5|Identifies as male|http://loinc.org|LA4489-6|Unknown|http://loinc.org|en|English|urn:ietf:bcp:47|2028-9|Asian|urn:oid:2.16.840.1.113883.6.238|2135-2|Hispanic or Latino|urn:oid:2.16.840.1.113883.6.238|AA12345C|2024-02-23T00:00:00.00Z|MTH|Mother|http://terminology.hl7.org/CodeSystem/v2-0063|Joyce|Doe|Phone|1234567890
                """;
        return CsvConversionUtil.convertCsvStringToDemographicData(csv).get(0);
    }

    private List<ScreeningData> createScreeningData() throws IOException {
        String csv = """
                PATIENT_MR_ID|FACILITY_ID|ENCOUNTER_ID|ENCOUNTER_CLASS_CODE|ENCOUNTER_CLASS_CODE_DESCRIPTION|ENCOUNTER_CLASS_CODE_SYSTEM|ENCOUNTER_STATUS_CODE|ENCOUNTER_STATUS_CODE_DESCRIPTION|ENCOUNTER_STATUS_CODE_SYSTEM|ENCOUNTER_TYPE_CODE|ENCOUNTER_TYPE_CODE_DESCRIPTION|ENCOUNTER_TYPE_CODE_SYSTEM|ENCOUNTER_START_TIME|ENCOUNTER_END_TIME|ENCOUNTER_LAST_UPDATED|LOCATION_NAME|LOCATION_STATUS|LOCATION_TYPE_CODE|LOCATION_TYPE_SYSTEM|LOCATION_ADDRESS1|LOCATION_ADDRESS2|LOCATION_CITY|LOCATION_DISTRICT|LOCATION_STATE|LOCATION_ZIP|LOCATION_PHYSICAL_TYPE_CODE|LOCATION_PHYSICAL_TYPE_SYSTEM|SCREENING_STATUS_CODE|SCREENING_CODE|SCREENING_CODE_DESCRIPTION|SCREENING_CODE_SYSTEM_NAME|RECORDED_TIME|QUESTION_CODE|QUESTION_CODE_DESCRIPTION|QUESTION_CODE_SYSTEM_NAME|UCUM_UNITS|SDOH_DOMAIN|PARENT_QUESTION_CODE|ANSWER_CODE|ANSWER_CODE_DESCRIPTION|ANSWER_CODE_SYSTEM_NAME
                11223344|CUMC|EncounterExample|FLD|field|http://terminology.hl7.org/CodeSystem/v3-ActCode|finished|Finished|http://terminology.hl7.org/CodeSystem/v3-ActCode|405672008|Direct questioning (procedure)|http://snomed.info/sct|2024-02-23T00:00:00Z|2024-02-23T00:00:00Z|2024-02-23T00:00:00Z|downtown location|active|CSC|http://terminology.hl7.org/CodeSystem/v3-RoleCode|115 Broadway Suite #1601||New York|MANHATTAN|NY|10006|bu|http://terminology.hl7.org/CodeSystem/location-physical-type|unknown|96777-8|Accountable health communities (AHC) health-related social needs screening (HRSN) tool|http://loinc.org|2023-07-12T16:08:00.000Z|71802-3|What is your living situation today?|http://loinc.org||Homelessness, Housing Instability||LA31993-1|I have a steady place to live|http://loinc.org
                """;
        return CsvConversionUtil.convertCsvStringToScreeningData(csv);
    }

    // Helper method to create QeAdminData from CSV data
    private QeAdminData createQeAdminData() throws IOException {
        String csv = """
                PAT_MRN_ID|FACILITY_ID|FACILITY_LONG_NAME|ORGANIZATION_TYPE|FACILITY_ADDRESS1|FACILITY_ADDRESS2|FACILITY_CITY|FACILITY_STATE|FACILITY_ZIP|VISIT_PART_2_FLAG|VISIT_OMH_FLAG|VISIT_OPWDD_FLAG
                qcs-test-20240603-testcase4-MRN|CNYSCN|Crossroads NY Social Care Network|SCN|25 W 45th st|Suite 16|New York|New York|10036|No|No|No
                """;
        return CsvConversionUtil.convertCsvStringToQeAdminData(csv).get(0);
    }
}
