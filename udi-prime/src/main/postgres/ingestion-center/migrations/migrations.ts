import * as ic1 from "./migrate-basic-infrastructure.ts";
import * as ic2 from "./migrate-interaction-fhir-view.ts";
import * as ic3 from "./migrate-diagnostics-fhir-view.ts";

// Create an array containing the modules
const ic = [ic1, ic2, ic3];

// Export the array
export { ic };