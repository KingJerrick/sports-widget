import json
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

import fetch_calendar as calendar


class NbaScheduleTests(unittest.TestCase):
    def fetch(self, **overrides):
        game = {
            "id": 123,
            "date": "2026-10-23",
            "datetime": "2026-10-24T02:00:00.000Z",
            "status": "10:00 pm ET",
            "home_team": {"id": 10, "full_name": "Golden State Warriors"},
            "visitor_team": {"id": 14, "full_name": "Memphis Grizzlies"},
        }
        game.update(overrides)
        with patch.dict(calendar.SECRETS, {"BALLDONTLIE_KEY": "test-key"}), \
                patch.object(calendar, "http_get", return_value=json.dumps({"data": [game]})):
            return calendar.fetch_nba(
                {"balldontlieTeamId": 10, "durationMinutes": 180},
                datetime(2026, 10, 24, tzinfo=timezone.utc),
                datetime(2026, 10, 25, tzinfo=timezone.utc),
            )

    def test_uses_tipoff_for_window_and_beijing_day(self):
        event, = self.fetch()
        self.assertEqual(event["start"], "2026-10-24T02:00:00Z")
        start = calendar.to_utc(event["start"])
        self.assertEqual(start.astimezone(timezone(timedelta(hours=8))).isoformat(),
                         "2026-10-24T10:00:00+08:00")
        self.assertGreater(start, datetime(2026, 10, 23, 2, tzinfo=timezone.utc))

    def test_offset_is_normalized_to_utc(self):
        self.assertEqual(self.fetch(datetime="2026-10-23T22:00:00-04:00")[0]["start"],
                         "2026-10-24T02:00:00Z")

    def test_unknown_time_never_falls_back_to_date(self):
        for value in (None, "", "TBD", "2026-10-24", "2026-10-24T02:00:00"):
            with self.subTest(value=value):
                self.assertEqual(self.fetch(datetime=value), [])

    def test_final_score_is_preserved(self):
        event, = self.fetch(status="Final", home_team_score=110, visitor_team_score=105)
        self.assertEqual(event["result"], "110-105")
        self.assertTrue(event["win"])

    def test_window_excludes_later_tipoff(self):
        self.assertEqual(self.fetch(datetime="2026-10-26T02:00:00Z"), [])


if __name__ == "__main__":
    unittest.main()
