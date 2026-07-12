# ADR 001: Solution B

Status: accepted.

Use AIRI as the desktop stage and renderer host, while `meguri-core` remains the only character, state, prompt, RAG, memory, and turn authority. AstrBot and the website are adapters. Do not fork AIRI as a competing backend.

