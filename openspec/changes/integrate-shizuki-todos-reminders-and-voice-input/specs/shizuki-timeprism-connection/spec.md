## Purpose

Connects the native Meguri desktop to the owner's Shizuki account so that TimePrism data can be read and changed under the correct user identity without leaking account credentials.

## ADDED Requirements

### Requirement: Explicit account connection
Meguri SHALL provide an explicit account connection flow using the configured HTTPS Shizuki site origin. It SHALL show whether the account is connected and allow the user to disconnect.

#### Scenario: Successful login
- **WHEN** the user submits valid Shizuki account credentials in the connection UI
- **THEN** Meguri shows the connected account identity and can read that account's TimePrism data

#### Scenario: Login failure
- **WHEN** the site rejects credentials or is unavailable
- **THEN** Meguri shows a useful error and does not claim to be connected

### Requirement: Token confidentiality and renewal
Meguri MUST keep Shizuki access and refresh tokens outside renderer storage, model context, and logs. It SHALL store a refresh token only in OS protected local storage, renew access automatically, and persist each rotated refresh token before relying on it for later requests.

#### Scenario: Access token expires
- **WHEN** a connected account's access token has expired and the refresh token remains valid
- **THEN** Meguri obtains a replacement token and continues the requested TimePrism action without asking for the password again

#### Scenario: User disconnects
- **WHEN** the user disconnects the account
- **THEN** locally stored Shizuki tokens are removed and subsequent TimePrism actions require a new connection

### Requirement: Constrained site access
Meguri SHALL call only its configured Shizuki origin and the required auth and TimePrism routes. It MUST reject insecure non-loopback origins and MUST NOT use the Meguri Core token as a Shizuki user token.

#### Scenario: Unsafe origin
- **WHEN** a user tries to connect to a non-loopback HTTP origin
- **THEN** Meguri rejects the origin before sending credentials
