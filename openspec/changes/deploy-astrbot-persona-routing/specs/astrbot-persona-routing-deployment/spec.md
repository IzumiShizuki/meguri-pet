## Purpose

Activate persona backend routing on the existing personal AstrBot instance with preserved configuration, recoverable plugin changes and verifiable routing evidence.

## ADDED Requirements

### Requirement: Preserve the deployed bot and configuration
Deployment SHALL affect only the necessary gateway files and the two persona-routing configuration fields on 111.228.35.186. Existing effective default chat SHALL continue to use Meguri, and other persona IDs SHALL use native AstrBot. Other plugin settings, destinations, owner permissions, Core configuration, image, network and mounts MUST remain unchanged.

#### Scenario: Existing default and native persona
- **WHEN** deployment enables persona routing for the verified current default ID
- **THEN** ordinary default-persona chat routes to Core and a different persona remains with AstrBot

#### Scenario: Existing owner and presentation settings
- **WHEN** the new routing fields are installed
- **THEN** enabled Todo/report settings, reminder settings and configuration-panel field visibility retain their previous values

### Requirement: Recoverable activation
Before replacing live files, the deployment MUST retain exact original files/configuration and rehearse their restoration outside live paths. Activation SHALL restart only the existing AstrBot container and restore the prior plugin/configuration if verification fails.

#### Scenario: Backup and restore rehearsal
- **WHEN** the candidate is ready to install
- **THEN** each original file and configuration can be restored with its recorded original hash

#### Scenario: Failed activation
- **WHEN** the new gateway fails startup or routing checks
- **THEN** restore the backed-up plugin/configuration and return AstrBot to its previous running state

### Requirement: Verify without platform sends
Validation MUST verify deployed hashes, startup success, native/default/explicit command routing and existing Core health while sending zero real platform test messages. Evidence SHALL distinguish synthetic routing checks from real QQ receipt.

#### Scenario: Successful activation
- **WHEN** the plugin is activated
- **THEN** live startup reports version 0.8.0, the updated files match the reviewed candidate, Core remains healthy and isolated routing checks pass
