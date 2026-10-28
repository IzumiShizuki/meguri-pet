## Purpose

This capability turns the desktop microphone control into an explicit spoken conversation session. Each locally recognized utterance becomes a normal Meguri message, and the session listens again after the assistant response.

## ADDED Requirements

### Requirement: Start and end a spoken conversation
The desktop chat composer MUST provide a visible control that starts a long-call session and changes into an explicit end-call control while the session is active. The interface MUST distinguish listening, waiting for Meguri, and speaking states. The session MUST end when the user hangs up or the chat surface is removed.

#### Scenario: Start a call
- **WHEN** the user activates the microphone control while no reply is in progress
- **THEN** Meguri starts local speech recognition and shows that it is listening

#### Scenario: Hang up while listening
- **WHEN** the user ends an active call while speech recognition is running
- **THEN** Meguri stops the local recognition process and returns the control to its idle state

#### Scenario: Hang up while Meguri is responding
- **WHEN** the user ends an active call while Meguri is generating or speaking a reply
- **THEN** Meguri cancels the active conversation turn and stops speech playback

### Requirement: Exchange completed utterances automatically
An active call MUST send each completed recognition result exactly once through the existing conversation flow without requiring a separate Send action. Meguri MUST pause recognition while generating or speaking a reply, then resume listening when the turn and any enabled speech playback are complete. An interval with no recognized speech MUST leave the call active and resume listening. A recognition or send failure MUST be shown to the user and MUST stop the call without retrying the same utterance automatically.

#### Scenario: Complete a voice turn
- **WHEN** local recognition returns a non-empty completed utterance during an active call
- **THEN** Meguri sends the transcript through the normal conversation flow, displays the message, and shows that it is waiting for a reply

#### Scenario: Resume after the assistant reply
- **WHEN** Meguri finishes generating a reply and its enabled local speech playback finishes
- **THEN** the active call resumes local speech recognition

#### Scenario: No speech is detected
- **WHEN** a recognition cycle completes without speech during an active call
- **THEN** Meguri starts another recognition cycle and keeps the call active

#### Scenario: Recognition or conversation fails
- **WHEN** local recognition fails or the existing conversation flow rejects the transcript
- **THEN** Meguri shows the failure and stops the call without resubmitting the transcript

### Requirement: Respect existing privacy and speech settings
The desktop MUST process microphone audio with the existing local recognition engine and MUST NOT save or upload microphone audio. Only a completed transcript MAY be sent, and it MUST use the existing conversation consent and privacy controls. Assistant playback MUST follow the existing local speech preferences; when speech playback is disabled, the text reply MUST remain visible and the call MUST resume listening after the conversation turn completes.

#### Scenario: Keep audio local
- **WHEN** a call is listening or processing an utterance
- **THEN** microphone audio remains on the device and only the completed transcript enters the existing chat flow

#### Scenario: Chat consent is unavailable
- **WHEN** a call recognizes speech while existing chat consent is unavailable
- **THEN** the existing conversation flow rejects the transcript and the call reports the failure without bypassing consent

#### Scenario: Reply speech is disabled
- **WHEN** Meguri completes a reply while local speech playback is disabled in settings
- **THEN** the reply remains available as text and the active call resumes listening
