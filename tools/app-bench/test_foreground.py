import unittest
import run  # Adds the shared tools directory, as the collector does.
from bench_device import PACKAGE, foreground_ready, parse_foreground


class ForegroundTest(unittest.TestCase):
    def state(self, wake='Awake', lock='false', package=PACKAGE):
        return parse_foreground(f'  mWakefulness={wake}\n',
                                f'mDreamingLockscreen={lock}\nmCurrentFocus=Window{{abc u0 {package}/.MainActivity}}')

    def test_awake_unlocked_and_focused(self):
        self.assertTrue(foreground_ready(self.state()))

    def test_dozing_is_not_awake(self):
        self.assertFalse(foreground_ready(self.state(wake='Dozing')))

    def test_locked_even_when_awake(self):
        self.assertFalse(foreground_ready(self.state(lock='true')))

    def test_other_foreground_app(self):
        self.assertFalse(foreground_ready(self.state(package='com.miui.home')))

    def test_unknown_or_inconsistent_state_is_rejected(self):
        self.assertFalse(foreground_ready(parse_foreground('', '')))
        self.assertFalse(foreground_ready(parse_foreground('mWakefulness=Awake',
                         'mDreamingLockscreen=false mDreamingLockscreen=true')))

    def test_notification_contents_not_retained(self):
        state = parse_foreground('mWakefulness=Awake',
                                 'mCurrentFocus=Window{abc u0 NotificationShade}\nmDreamingLockscreen=true\nprivate text')
        self.assertIsNone(state['focused_package'])
        self.assertNotIn('private', str(state))
