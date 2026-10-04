# Meguri desktop AIRI parity backlog

The native desktop currently mirrors the AIRI fork's frameless stage,
bottom-right controls island, embedded chat geometry, appearance modes, outfit
picker, window actions, speech playback preference, shortcuts, and independent
settings window.

These AIRI feature families intentionally remain out of the native settings
catalog until Meguri owns an equivalent runtime capability:

- Provider and model-account configuration
- Plugin discovery and lifecycle management
- Application updater controls
- Live2D, VRM, and Spine renderer settings
- AIRI profile/card authentication and profile switching
- Prompt-cache administration and Codex-progress overlays
- Microphone device selection and transcription controls

Window snapping and target-window attachment are planned as a separate desktop
window-management change; the current centering and persisted bounds APIs are
the extension seam for that work.
