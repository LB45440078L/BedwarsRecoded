# The lobby (hub) world — optional

Drop a Minecraft world directory here (a `level.dat` plus its `region/` files) and the
container will stage it as the lobby's main world, exactly as it stages `Glacier/` for a
game pod.

This folder is empty on purpose: no hub map is shipped, so a fresh checkout has nothing to
stage and the lobby generates a flat world instead (see `BEDWARS_LEVEL_TYPE=flat` in
`deploy/compose/docker-compose.yml`). That keeps the image small and avoids shipping a
build nobody asked for.

## How to use it

1. Build your hub in a world, then stop the server cleanly (so `level.dat` is flushed).
2. Copy the world directory here as `deploy/templates/lobby/`:
   ```
   cp -a /path/to/your-hub-world/. deploy/templates/lobby/
   ```
   The folder contents — not the folder itself — are what gets staged, so `level.dat` must
   sit directly inside it.
3. Make sure `BEDWARS_TEMPLATE_NAME=lobby` and `BEDWARS_TEMPLATE_SOURCE=LOCAL` on the lobby
   service (they already are, in the compose file and the Helm chart).

On the next start the container logs `hub world ready: <size> at /server/world` instead of the
"no hub world staged" warning. Put your matchmaking signs (first line `[bedwars]`) and NPCs
(named `[bedwars]`) in this world — they are the lobby's whole interface.

Nothing else changes: the plugin needs no configuration to recognise them, and the hub's
protection rules apply to whatever world this is staged as.
