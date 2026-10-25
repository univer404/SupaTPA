# SupaTPA

A configurable teleport-request plugin for Paper and Purpur servers. Designed for Minecraft 1.21.11 and Java 21.

## Features
- `/tpa <player>` — ask to teleport to a player.
- `/tpahere <player>` — ask a player to teleport to you.
- `/tpaccept [player]` — open an accept menu for a pending request.
- `/tpacancel <player>` — deny a pending request.
- `/tpatoggle` — toggle incoming TPA requests.
- `/tpaheretoggle` — toggle incoming TPA-here requests.
- Configurable confirmation menus in `plugins/SupaTPA/gui.yml`.
- Editable messages in `plugins/SupaTPA/messages.yml`.
- Request cooldown, 60-second request expiry, teleport countdown, and movement cancellation.
- Standard inventory menus and text commands work with Java players and Bedrock players connected through Geyser/Floodgate.

## Install
1. Use Java 21 and Paper/Purpur 1.21.11.
2. Put `SupaTPA.jar` in the server's `plugins` directory.
3. Restart the server.
4. Edit `config.yml`, `gui.yml`, and `messages.yml` inside `plugins/SupaTPA`.
5. Restart the server after configuration changes.

## Build
Run `mvn clean package`. The output is `target/SupaTPA.jar`.

## Notes
Teleport requests are held in memory and are cleared on server restart. A teleport is cancelled if the moving player changes position by more than 0.5 blocks during the countdown. This plugin does not require Skript, Vault, or PlaceholderAPI.
