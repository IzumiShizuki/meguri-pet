## Purpose

Provide user-controlled native conversation appearance through safe local wallpapers, blur, bubble opacity and reusable color palettes without changing desktop transparency.

## ADDED Requirements

### Requirement: Wallpaper and bubble controls
The application SHALL import a bounded local image as the chat background, preview it, adjust background blur from 0 to 100 percent and bubble opacity from 20 to 100 percent, and restore the default background. Changes MUST persist and apply live to conversation surfaces while keeping the surrounding desktop pet transparent.

#### Scenario: Personalize conversation
- **WHEN** the user imports an allowed wallpaper and adjusts blur or opacity
- **THEN** the conversation previews and applies those values immediately and restores them after restart.

#### Scenario: Invalid image
- **WHEN** an image exceeds supported limits or fails decoding
- **THEN** it is rejected with an explanation and the existing wallpaper remains intact.

### Requirement: Preset and system colors
The app SHALL provide multiple named palette presets, a system accent option where available, and existing system/light/dark theme modes. Text, controls and focus indicators MUST remain readable across supported combinations.

#### Scenario: Change palette
- **WHEN** the user selects a palette or changes system color mode
- **THEN** conversation surfaces and settings apply consistent colors without losing text or control contrast.
