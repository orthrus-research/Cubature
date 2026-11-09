"""Where Cubature's tools find the Riven workspace they build for and measure in.

RIVEN_WORKSPACE names it (Riven's tools export it); without it, a sibling clone ../Riven.
A tool's --workspace or --riven-workspace argument still overrides both.
"""

import os
from pathlib import Path

FORK = Path(__file__).resolve().parents[1]


def riven_workspace() -> Path:
    named = os.environ.get("RIVEN_WORKSPACE")
    return Path(named).resolve() if named else FORK.parent / "Riven"
