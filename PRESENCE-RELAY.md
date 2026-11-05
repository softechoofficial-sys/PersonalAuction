# Private PersonalAuction player presence

The Build 121 compatibility source on this branch uses the same private presence protocol as the current Build 146 branch.

For detection to work, install a compatible PersonalAuction build on the Minecraft server as well as on the clients. The server forwards the custom presence payload only to clients that support it. No chat messages or public indicators are sent; detected names are shown only inside the PersonalAuction screen.
