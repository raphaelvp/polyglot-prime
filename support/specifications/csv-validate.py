import sys
import json
import os
import glob
from frictionless import Package, Report, transform, steps

# Function to find files based on prefix
def find_file(prefix, base_dir):
    files = glob.glob(os.path.join(base_dir, f"{prefix}*.csv"))
    return files[0] if files else None

def validate_package(spec_path, data_dir, output_path):
    results = {
        "errorsSummary": [],
        "report": None
    }

    try:
        # Load the schema definitions from spec.json
        with open(spec_path) as f:
            spec = json.load(f)

        # Map prefixes to resources based on file names
        file_mappings = {
            "qe_admin_data": find_file("QE_ADMIN_DATA", data_dir),
            "screening_data": find_file("SCREENING", data_dir),
            "demographic_data": find_file("DEMOGRAPHIC_DATA", data_dir)
        }

        # Check for missing files
        missing_files = {key: prefix for key, prefix in file_mappings.items() if not prefix}
        if missing_files:
            for resource_name in missing_files:
                results["errorsSummary"].append({
                    "rowNumber": None,
                    "fieldNumber": None,
                    "fieldName": resource_name,
                    "message": f"File for resource '{resource_name}' not found.",
                    "type": "file-missing-error"
                })

            # Write errors to output.json and skip further processing
            with open(output_path, 'w') as json_file:
                json.dump(results, json_file, indent=4)

            print(f"Validation skipped due to missing files. Results saved to '{output_path}'.")
            return  # Skip Frictionless validation

        #print(file_mappings)

        # Create the package descriptor dynamically, inserting paths from `file_mappings`
        resources = []
        for resource in spec["resources"]:
            # Ensure the file exists for the given resource name
            path = file_mappings.get(resource["name"])
            if not path:
                raise FileNotFoundError(f"File for resource '{resource['name']}' not found.")

            # Update the resource dictionary with the path
            resource_with_path = {**resource, "path": path}
            resources.append(resource_with_path)

        # Construct the final package descriptor with dynamic paths
        package_descriptor = {
            "name": "csv-validation-using-ig",
            "resources": resources
        }

        # Load the package with Frictionless
        package = Package(package_descriptor)

        common_transform_steps = [
            ("ORGANIZATION_TYPE", "organization_type"),
            ("FACILITY_STATE", "facility_state"),
            ("ENCOUNTER_CLASS_CODE", "encounter_class_code"),
            ("ENCOUNTER_CLASS_CODE_DESCRIPTION", "encounter_class_code_description"),
            ("ENCOUNTER_STATUS_CODE", "encounter_status_code"),
            ("ENCOUNTER_STATUS_CODE_DESCRIPTION", "encounter_status_code_description"),
            ("ENCOUNTER_TYPE_CODE_DESCRIPTION", "encounter_type_code_description"),
            ("SCREENING_STATUS_CODE", "screening_status_code"),
            ("SCREENING_CODE_DESCRIPTION", "screening_code_description"),
            ("QUESTION_CODE_DESCRIPTION", "question_code_description"),
            ("UCUM_UNITS", "ucum_units"),
            ("SDOH_DOMAIN", "sdoh_domain"),
            ("ANSWER_CODE", "answer_code"),
            ("ANSWER_CODE_DESCRIPTION", "answer_code_description"),
            ("GENDER", "gender"),
            ("SEX_AT_BIRTH_CODE", "sex_at_birth_code"),
            ("SEX_AT_BIRTH_CODE_DESCRIPTION", "sex_at_birth_code_description"),
            ("SEX_AT_BIRTH_CODE_SYSTEM", "sex_at_birth_code_system"),
            ("RELATIONSHIP_PERSON_CODE", "relationship_person_code"),
            ("RELATIONSHIP_PERSON_DESCRIPTION", "relationship_person_description"),
            ("STATE", "state"),
            ("GENDER_IDENTITY_CODE", "gender_identity_code"),
            ("GENDER_IDENTITY_CODE_DESCRIPTION", "gender_identity_code_description"),
            ("GENDER_IDENTITY_CODE_SYSTEM_NAME", "gender_identity_code_system_name"),
            ("SEXUAL_ORIENTATION_CODE", "sexual_orientation_code"),
            ("SEXUAL_ORIENTATION_CODE_DESCRIPTION", "sexual_orientation_code_description"),
            ("PREFERRED_LANGUAGE_CODE", "preferred_language_code"),
            ("PREFERRED_LANGUAGE_CODE_DESCRIPTION", "preferred_language_code_description"),
            ("PREFERRED_LANGUAGE_CODE_SYSTEM_NAME", "preferred_language_code_system_name"),
            ("RACE_CODE_DESCRIPTION", "race_code_description"),
            ("ETHNICITY_CODE_DESCRIPTION", "ethnicity_code_description"),
            ("ETHNICITY_CODE_SYSTEM_NAME", "ethnicity_code_system_name")
        ]

        for resource in package.resources:
            # Create transform steps only for fields that exist in the current resource
            transform_steps = [
                steps.cell_convert(field_name=field_name, function=lambda value: value.lower())
                for field_name, _ in common_transform_steps
                if any(field.name == field_name for field in resource.schema.fields)
            ]
            resource = transform(resource, steps=transform_steps)

        # Validate the package
        report = package.validate()

        # Add the validation report to results
        results["report"] = report.to_dict()

        # for error in report.flatten(["rowNumber", "fieldNumber", "fieldName", "message", "type"]):
        #     results["errorsSummary"].append({
        #         "rowNumber": error[0],
        #         "fieldNumber": error[1],
        #         "fieldName": error[2],
        #         "message": error[3],
        #         "type": error[4]
        #     })

    except FileNotFoundError as e:
        # Log any file-related errors to the output JSON
        results["errorsSummary"].append({
            "rowNumber": None,
            "fieldNumber": None,
            "fieldName": None,
            "message": str(e),
            "type": "file-missing-error"
        })

    except Exception as e:
        # Log unexpected errors to the output JSON
        results["errorsSummary"].append({
            "rowNumber": None,
            "fieldNumber": None,
            "fieldName": None,
            "message": str(e),
            "type": "unexpected-error"
        })

    # Write the results to a JSON file
    with open(output_path, 'w') as json_file:
        json.dump(results, json_file, indent=4)

    # Print a success or error message to the console
    if results["errorsSummary"]:
        print(f"Validation completed with errors. Results saved to '{output_path}'.")
    else:
        print(f"Validation completed successfully. Results saved to '{output_path}'.")



if __name__ == "__main__":
    results = {
        "errorsSummary": [],
        "report": None
    }

    # Check for the correct number of arguments
    if len(sys.argv) != 4:
        error_message = "Usage: python3 csv-validate.py <spec_path> <data_dir> <output_path>"
        results["errorsSummary"].append({
            "rowNumber": None,
            "fieldNumber": None,
            "fieldName": None,
            "message": error_message,
            "type": "argument-error"
        })
        with open("output.json", 'w') as json_file:
            json.dump(results, json_file, indent=4)
        print(error_message)
        sys.exit(1)

    # Parse arguments
    spec_path = sys.argv[1]
    data_dir = sys.argv[2]
    output_path = sys.argv[3] 

    # Check if `output_path` is valid and set default if not
    if not output_path.endswith('.json'):
        print(f"Warning: Provided output path '{output_path}' is not a valid JSON file. Defaulting to 'output.json'.")
        output_path = "output.json"

    # Check if the paths exist
    if not os.path.isfile(spec_path):
        error_message = f"Error: Specification file '{spec_path}' not found."
        results["errorsSummary"].append({
            "rowNumber": None,
            "fieldNumber": None,
            "fieldName": None,
            "message": error_message,
            "type": "file-missing-error"
        })
        with open(output_path, 'w') as json_file:
            json.dump(results, json_file, indent=4)
        print(error_message)
        sys.exit(1)

    if not os.path.isdir(data_dir):
        error_message = f"Error: Data directory '{data_dir}' not found."
        results["errorsSummary"].append({
            "rowNumber": None,
            "fieldNumber": None,
            "fieldName": None,
            "message": error_message,
            "type": "directory-missing-error"
        })
        with open(output_path, 'w') as json_file:
            json.dump(results, json_file, indent=4)
        print(error_message)
        sys.exit(1)

    # Try validation
    try:
        validate_package(spec_path, data_dir, output_path)
        #print(f"Validation completed. Results saved to '{output_path}'.")
    except Exception as e:
        error_message = f"Error during validation: {e}"
        results["errorsSummary"].append({
            "rowNumber": None,
            "fieldNumber": None,
            "fieldName": None,
            "message": error_message,
            "type": "validation-error"
        })
        with open(output_path, 'w') as json_file:
            json.dump(results, json_file, indent=4)
        print(error_message)
        sys.exit(1)
