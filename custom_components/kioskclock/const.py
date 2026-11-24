"""Constants for the Kiosk Clock integration."""

from __future__ import annotations

import logging
from typing import Final

DOMAIN: Final = "kioskclock"
LOGGER = logging.getLogger(__package__)

DEFAULT_PORT: Final = 8765
DEFAULT_NAME: Final = "Smart Clock 2"
CONF_TOKEN: Final = "token"
CONF_WEBHOOK_ID: Final = "webhook_id"

# Fired on the bus for every event the clock pushes: {device_id, type, value, trigger}.
EVENT_KIOSKCLOCK: Final = "kioskclock_event"
SIGNAL_EVENT: Final = "kioskclock_event_{}"  # dispatcher signal, formatted with the entry id

# Settings exposed by GET/POST /api/settings and the options offered for each.
SETTING_MIC_INDICATOR: Final = "kioskclock_mic_indicator"
SETTING_MIC_COLOR: Final = "kioskclock_mic_color"
SETTING_MIC_SIZE: Final = "kioskclock_mic_size"
SETTING_HOLD_UP: Final = "kioskclock_hold_volume_up"
SETTING_HOLD_DOWN: Final = "kioskclock_hold_volume_down"
SETTING_TAP_SINGLE: Final = "kioskclock_tap_single"
SETTING_TAP_DOUBLE: Final = "kioskclock_tap_double"
SETTING_ALARM_TAP_SINGLE: Final = "kioskclock_alarm_tap_single"
SETTING_ALARM_TAP_DOUBLE: Final = "kioskclock_alarm_tap_double"
ALARM_TAP_ACTIONS: Final = ["alarm_snooze", "alarm_stop", "none"]
SETTING_DOUBLE_TAP_MS: Final = "kioskclock_double_tap_ms"
SETTING_WAKE_LIGHT: Final = "kioskclock_wake_light"
SETTING_WAKE_MINUTES: Final = "kioskclock_wake_minutes"
SETTING_WAKE_MAX: Final = "kioskclock_wake_max"
# option -> value on the clock (None = default, off)
WAKE_LIGHT_MODES: Final = {"off": None, "dock": "dock", "ha": "ha", "both": "both"}
# Integration options: HA lights the wake-up light fades in, and how bright they get.
CONF_WAKE_LIGHTS: Final = "wake_lights"
CONF_WAKE_MAX_PCT: Final = "wake_max_pct"

# "default" resets a setting to the clock's default (ClockBridge 0.9.0+).
DEFAULT_VALUE: Final = "default"

MIC_INDICATORS: Final = ["dot", "dot_quiet", "google", "pill", "edge", "banner", "hidden"]
# option -> value sent to the clock (None = clock default)
MIC_COLORS: Final = {"red": "#D93025", "amber": "#F29900", "white": "#FFFFFF", "dim": "#66FFFFFF"}
MIC_SIZES: Final = {"small": "10", "default": None, "large": "22"}
DOUBLE_TAP_SPEEDS: Final = {"fast": "300", "normal": None, "relaxed": "700"}
# What a gesture (Vol+/Vol- hold, tap / double tap on top) can do on the clock.
GESTURE_ACTIONS: Final = [
    "alarm_snooze", "alarm_stop", "voice", "play_pause", "mute", "dock_light", "night_light", "screen_off", "screen_toggle", "ha_event", "none",
]
GESTURES: Final = ["hold_up", "hold_down", "tap_single", "tap_double"]
# /api/gesture, /api/action and the tap settings (ClockBridge 0.9.0, API revision 3).
API_GESTURES: Final = 3
