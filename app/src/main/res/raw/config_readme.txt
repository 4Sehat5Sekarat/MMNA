MPD MPRIS - Config Guide

FILES
  config.json   Your settings. Edit this file.
  README.txt    This guide. Recreated automatically if missing.

HOW TO EDIT
  1. Turn the quick settings tile OFF. The config folder is only accessible while the app is off.
  2. Open your file manager and go to the "MPD Config" root.
  3. Edit config.json. It must be valid JSON. Do not add comments.
  4. Turn the tile ON. Settings are read once when the app starts.

If the config has an error, the app does not start and a message shows the reason.

SETTINGS
  mpd.host              MPD address. Default: 127.0.0.1
  mpd.port              MPD port, 1-65535. Default: 6600
  mpd.password          MPD password. Empty if none. Default: ""
  mpd.timeout_ms        Socket timeout in milliseconds, greater than 0. Default: 5000
  idle_timeout_sec      Seconds to keep retrying a lost MPD connection before the app stops. Default: 60
  reconnect_delay_ms    Delay between reconnect attempts, minimum 500. Default: 2000
  album_art             true or false. Show album art in the notification. Default: true
  art_max_size          Longest side of the cover in px, 64-1024. Smaller is faster. Default: 256
  art_preload           off, queue, or library. See PRELOAD. Default: queue
  events                Action for each event. See EVENTS.
  notification_buttons  Buttons in the notification, in order, maximum 5. Default: ["prev", "play", "next"]
  volume_keys           system or mpd. See VOLUME. Default: system
  volume_step           Volume step in percent when volume_keys is mpd, 1-50. Default: 5

EVENTS
  Events:  wired_disconnect, wired_connect, bt_disconnect, bt_connect
  Actions: pause, play, stop
  Example: "events": { "bt_disconnect": "pause", "bt_connect": "play" }
  wired_*  wired headset or headphones
  bt_*     Bluetooth audio (A2DP or SCO)

BUTTONS
  Available: prev, play, next, stop, repeat, random
  The collapsed notification shows the first 3 buttons.
  Repeat cycles: off -> all -> one -> off
  Random toggles shuffle.

VOLUME
  system  Volume keys and the volume slider control the Android media volume.
  mpd     Volume keys and the volume slider control the MPD volume.
          MPD must have a mixer. Otherwise this setting is ignored.

PRELOAD
  off       Covers are fetched only when the track changes.
  queue     Covers for folders in the play queue are fetched in the background
            when the app starts and when the queue changes. Default.
  library   Covers for every folder in the MPD library are fetched in the background
            when the app starts. This can take a while on large libraries.
  Only works when album_art is true.

RESET TO DEFAULT
  1. Turn the tile OFF.
  2. Delete config.json.
  3. Long-press the tile. A new config.json with the defaults is created.
  4. Optional: delete README.txt too. It is recreated automatically.