# AntiCheatAI 1.0.0

Paper 1.21+ anti-cheat/training plugin focused on real-time gameplay telemetry, adaptive anomaly scoring and per-viewer ESP masking.

## Commands
- `/ac train <player>` — record live movement/camera/combat telemetry.
- `/ac train stop` — stop all training sessions and flush CSV files.
- `/ac watch <player>` — start live monitoring/recording for a player.
- `/ac status <player>` — current score/check/baseline status.
- `/ac report <player>` — extended status.
- `/ac alerts` — toggle admin alerts.
- `/ac reset <player>` — reset the live score.
- `/ac reload` — reload config.

Training CSV files are saved under `plugins/AntiCheatAI/training/`.

The "AI" is intentionally an adaptive anomaly scorer: it learns a player's own movement/rotation baseline and combines it with deterministic checks. It is not a remote LLM and does not require an external API.

By default, punishment is a delayed kick after the configured suspicion threshold. No bans are performed.

## Build
Requires Java 21 and Maven. Run:
`mvn package`
The resulting JAR is in `target/AntiCheatAI-1.0.0.jar`.
