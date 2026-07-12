# ADR 002: Turn event protocol

Status: accepted.

Use HTTP to create turns and SSE first for stable ordered event envelopes. Every envelope carries `turn_id`, `session_id`, monotonic `sequence`, `trace_id`, timestamp, source, and dataset `build_id`. Keep the synchronous endpoint for compatibility. WebSocket may be added without changing the envelope.

