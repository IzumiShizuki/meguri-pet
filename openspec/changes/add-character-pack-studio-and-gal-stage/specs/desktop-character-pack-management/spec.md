## Purpose

Provide a safe, local-first character presentation library so desktop users can import, inspect, curate, and activate PNG character packs without exposing local files or executable content to Meguri Core.

## ADDED Requirements

### Requirement: Users can inspect a local character presentation catalog
The desktop client SHALL present imported and draft character packs in a Character Studio, including each pack's display name, version, activation state, outfits, expressions, backgrounds, and validation status. The Studio SHALL render an actual local preview for resources that passed validation and SHALL identify unavailable resources without crashing the desktop client.

#### Scenario: Viewing an active character pack
- **WHEN** a user opens Character Studio and selects an active pack
- **THEN** the client displays its validated resources, active outfit, and a real preview without exposing the package's absolute filesystem path

#### Scenario: Viewing an unavailable resource
- **WHEN** an imported pack has a resource that cannot be rendered by the client
- **THEN** the Studio displays the resource as unavailable with a recoverable validation message and preserves the remaining valid resources

### Requirement: Users can import presentation resources as a draft
The desktop client SHALL allow a user to select a local character-pack directory or add supported PNG resources to a draft pack. The system MUST validate manifests, resource references, file types, file sizes, directory boundaries, and duplicate identifiers before the resources are available for activation.

#### Scenario: Importing a valid pack directory
- **WHEN** a user selects a directory containing a valid supported pack manifest and PNG resources
- **THEN** the client creates a draft pack, reports the discovered resources, and allows the user to preview it before publication

#### Scenario: Rejecting unsafe or invalid input
- **WHEN** a selected pack contains an unsupported file, an invalid resource reference, a traversal attempt, or an invalid manifest
- **THEN** the import is rejected with a resource-specific error and the active catalog remains unchanged

### Requirement: Users can publish, disable, and restore pack versions
The desktop client SHALL allow a validated draft pack to be published as a versioned local package, activated for future presentation, disabled, and restored from a retained version. Publishing, disabling, and restoring MUST not modify a presentation snapshot already in use by an active conversation.

#### Scenario: Publishing a draft pack
- **WHEN** a user publishes a validated draft pack
- **THEN** the client activates the selected version for subsequent presentation resolution and retains the previous active version for restoration

#### Scenario: Disabling a pack used by an active conversation
- **WHEN** a user disables a pack while a conversation is still rendering a snapshot from it
- **THEN** the active conversation keeps its existing resolved assets while later presentation resolution excludes the disabled pack
