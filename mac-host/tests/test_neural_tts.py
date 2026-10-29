"""Offline adapter integration: a fake provider writes bytes, never sends text online."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("oms_neural_tts", Path(__file__).resolve().parents[1] / "scripts/neural_tts.py")
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)


class AdapterTests(unittest.TestCase):
    def request(self, provider):
        self.directory = Path(tempfile.mkdtemp(prefix="oms-tts-adapter-"))
        output = self.directory / "voice.mp3"
        output.write_bytes(b"existing-complete-audio")
        req = {"text": "synthetic test phrase", "voice": "zh-CN-XiaoyiNeural", "rate": "+6%", "pitch": "+0Hz", "output": str(output)}
        with patch.dict("sys.modules", {"edge_tts": provider}), patch("sys.stdin", io.StringIO(json.dumps(req))):
            code = adapter.main()
        return code, output

    def test_success_passes_voice_and_atomically_replaces_cache(self):
        seen = {}
        class Speech:
            def __init__(self, text, voice, **opts):
                seen.update(text=text, voice=voice, **opts)
            async def save(self, target):
                Path(target).write_bytes(b"a" * 256)
        code, output = self.request(SimpleNamespace(Communicate=Speech))
        self.assertEqual(code, 0)
        self.assertEqual(seen["voice"], "zh-CN-XiaoyiNeural")
        self.assertEqual(seen["rate"], "+6%")
        self.assertEqual(output.read_bytes(), b"a" * 256)
        self.assertEqual(list(self.directory.glob("*.part")), [])

    def test_failed_synthesis_keeps_completed_cache_and_removes_its_partial_file(self):
        class Speech:
            def __init__(self, *args, **kwargs): pass
            async def save(self, target):
                Path(target).write_bytes(b"partial")
                raise RuntimeError("provider unavailable")
        code, output = self.request(SimpleNamespace(Communicate=Speech))
        self.assertEqual(code, 1)
        self.assertEqual(output.read_bytes(), b"existing-complete-audio")
        self.assertEqual(list(self.directory.glob("*.part")), [])

    def test_missing_dependency_is_distinct_from_network_failure(self):
        code, output = self.request(None)
        self.assertEqual(code, 3)
        self.assertEqual(output.read_bytes(), b"existing-complete-audio")


if __name__ == "__main__":
    unittest.main()
