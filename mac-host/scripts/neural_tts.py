"""Repository-owned Edge TTS adapter. Read one JSON request from stdin; never log text."""
import asyncio
import json
import os
from pathlib import Path
import sys
import tempfile


def main():
    try:
        import edge_tts
    except ImportError:
        return 3
    temporary = None
    try:
        request = json.load(sys.stdin)
        output = Path(request["output"])
        # A killed/failed synthesis must not leave a partial MP3 in the completed cache.
        with tempfile.NamedTemporaryFile(dir=output.parent, suffix=".part", delete=False) as f:
            temporary = Path(f.name)
        async def synthesize():
            speech = edge_tts.Communicate(request["text"], request["voice"],
                                          rate=request["rate"], pitch=request["pitch"])
            await speech.save(str(temporary))
        asyncio.run(synthesize())
        if temporary.stat().st_size <= 200:
            return 4
        os.replace(temporary, output)
        temporary = None
        return 0
    except Exception:
        return 1
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    sys.exit(main())
