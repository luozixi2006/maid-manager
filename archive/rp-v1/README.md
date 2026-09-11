# RP V1 archive

This directory is a source archive of the RP feature as it existed immediately
before the chat-focused rebuild. Files are stored under their original relative
paths so they can be compared or restored deliberately. Nothing under
`archive/rp-v1` belongs to an Android source set or the active application build.

Archived feature code includes the RP models, DataStore adapters, model catalog
and selection logic, engine, portrait integration, ViewModel, Compose screens,
handoff builder and RP unit tests. Snapshots of shared integration files are also
included to document the former navigation, settings, proactive-message and
notification hooks.

## Stored user data

Removing this code does not erase installed-app data. Existing RP saves remain
in the Preferences DataStore file `rp_worlds.preferences_pb` under the
`worlds_json` key. RP model configuration remains in
`rp_models.preferences_pb` under `rp_model_settings`. Portrait files remain
under the app's private `files/rp_worlds/<worldId>/portraits/` directory.

These files are private Android app data and are lost if the app is uninstalled,
its data is cleared, or its application ID changes. The ordinary provider store
is intentionally not removed: RP used the same provider records as chat, and
those providers and API keys may still be required by normal conversations.

## Restoration notes

Restore selectively rather than copying the archive wholesale. Reintroducing RP
requires the archived `rp` and `ui/rp` packages plus navigation/settings hooks,
the optional handoff builder, and (if desired) the RP branch of proactive
messages. Keep the current application ID and compatible serializers to read old
device data. The optional RP fields in the active handoff data model were left in
place for backward compatibility.
