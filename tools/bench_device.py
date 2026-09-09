"""Read-only foreground checks shared by Android benchmark collectors.

An `am start` success or a brief CPU boost does not prove the screen is awake
and unlocked. Retain only the required state, not notification/window contents.
"""
import re

PACKAGE = 'io.github.yinvoker.bergamot.bench'
FOREGROUND_CHECKS = 'awake-unlocked-focused-before-after-v1'


def parse_foreground(power, windows):
    wake = re.search(r'^\s*mWakefulness=(\w+)\s*$', power, re.M)
    locks = set(re.findall(r'\bmDreamingLockscreen=(true|false)\b', windows))
    focus = re.search(r'mCurrentFocus=Window\{[^\n]*?\bu\d+ ([\w.]+)/', windows)
    state = {'wakefulness': wake[1] if wake else None,
             'lockscreen': next(iter(locks)) == 'true' if len(locks) == 1 else None,
             'focused_package': focus[1] if focus else None}
    return state


def capture_foreground(shell):
    return parse_foreground(shell('dumpsys power'), shell('dumpsys window'))


def foreground_ready(state):
    return (isinstance(state, dict) and state.get('wakefulness') == 'Awake'
            and state.get('lockscreen') is False and state.get('focused_package') == PACKAGE)
