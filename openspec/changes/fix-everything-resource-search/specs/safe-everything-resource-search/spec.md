## Purpose

Allow users to select safe, metadata-only files returned by their locally configured Everything index regardless of the local drive on which those files reside.

## ADDED Requirements

### Requirement: Preserve safe indexed local-drive results

The system SHALL offer a local resource returned by Everything when its canonical path is on a local filesystem and passes the resource safety policy, regardless of its drive letter or directory root.

#### Scenario: Match on a non-workspace local drive

- **WHEN** Everything returns a matching regular file on a local drive outside the default workspace directories
- **THEN** the system offers the file as a metadata-only resource candidate

### Requirement: Retain protected-path exclusions

The system SHALL reject results that are not regular local files or directories, or whose canonical path is in a protected operating-system, application-data, credential, or runtime directory. It SHALL not read the file content while searching or presenting a candidate.

#### Scenario: Match in a protected directory

- **WHEN** Everything returns a matching file whose canonical path is in a protected directory
- **THEN** the system excludes the result and does not expose its content

### Requirement: Explain an empty safe result set

The system SHALL distinguish an empty Everything result set from results that were excluded by the local-resource safety policy, without disclosing protected path details.

#### Scenario: All indexed matches are unsafe

- **WHEN** Everything returns one or more matches and every match is excluded by the safety policy
- **THEN** the system reports that matching resources were found but could not be safely offered
